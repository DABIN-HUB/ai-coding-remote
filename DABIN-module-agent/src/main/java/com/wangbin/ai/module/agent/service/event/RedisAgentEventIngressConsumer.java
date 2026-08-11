package com.wangbin.ai.module.agent.service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.AgentEventIngressPayload;
import com.wangbin.ai.agent.contract.coordination.CommandAckIngressPayload;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Consumes reliable Agent ingress events from Redis Stream. The stream ACK is
 * issued only after AgentEventIngressService finishes its transactional update.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAgentEventIngressConsumer {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentEventIngressService ingressService;
    private final AgentControlPlaneProperties properties;
    private final String consumerName = UUID.randomUUID().toString();

    @PostConstruct
    public void ensureConsumerGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(AgentCoordinationKeys.eventIngressStream(),
                    ReadOffset.latest(), properties.getEventIngressConsumerGroup());
        } catch (RuntimeException ex) {
            if (ex.getMessage() == null || !ex.getMessage().contains("BUSYGROUP")) {
                log.debug("agent event ingress consumer group may already exist or stream is empty: group={}",
                        properties.getEventIngressConsumerGroup(), ex);
            }
        }
    }

    @Scheduled(fixedDelayString = "${agent.control-plane.event-ingress-poll-interval-millis:1000}")
    public void poll() {
        List<MapRecord<String, Object, Object>> records = readRecords();
        if (records == null || records.isEmpty()) {
            return;
        }
        records.forEach(this::handleRecord);
    }

    private List<MapRecord<String, Object, Object>> readRecords() {
        return stringRedisTemplate.opsForStream().read(
                Consumer.from(properties.getEventIngressConsumerGroup(),
                        properties.getEventIngressConsumerNamePrefix() + consumerName),
                StreamReadOptions.empty()
                        .count(properties.getEventIngressBatchSize())
                        .block(properties.getEventIngressReadBlockTime()),
                StreamOffset.create(AgentCoordinationKeys.eventIngressStream(), ReadOffset.lastConsumed()));
    }

    private void handleRecord(MapRecord<String, Object, Object> record) {
        String type = stringValue(record, AgentCoordinationKeys.EVENT_INGRESS_FIELD_TYPE);
        String payload = stringValue(record, AgentCoordinationKeys.EVENT_INGRESS_FIELD_PAYLOAD);
        try {
            if (AgentCoordinationKeys.EVENT_INGRESS_TYPE_AGENT_EVENT.equals(type)) {
                ingressService.handleAgentEvent(objectMapper.readValue(payload, AgentEventIngressPayload.class));
            } else if (AgentCoordinationKeys.EVENT_INGRESS_TYPE_COMMAND_ACK.equals(type)) {
                ingressService.handleCommandAck(objectMapper.readValue(payload, CommandAckIngressPayload.class).ack());
            } else {
                log.warn("unknown agent event ingress stream type: recordId={}, type={}", record.getId(), type);
                return;
            }
            stringRedisTemplate.opsForStream().acknowledge(AgentCoordinationKeys.eventIngressStream(),
                    properties.getEventIngressConsumerGroup(), record.getId());
        } catch (Exception ex) {
            log.warn("failed to consume agent ingress stream record: recordId={}, type={}", record.getId(), type, ex);
        }
    }

    private String stringValue(MapRecord<String, Object, Object> record, String key) {
        Object value = record.getValue().get(key);
        return value == null ? null : value.toString();
    }
}
