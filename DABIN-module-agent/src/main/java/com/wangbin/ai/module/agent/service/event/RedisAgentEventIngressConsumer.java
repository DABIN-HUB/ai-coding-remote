package com.wangbin.ai.module.agent.service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.AgentEventIngressPayload;
import com.wangbin.ai.agent.contract.coordination.CommandAckIngressPayload;
import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
                connection.streamCommands().xGroupCreate(raw(AgentCoordinationKeys.eventIngressStream()),
                        properties.getEventIngressConsumerGroup(), ReadOffset.from("0-0"), true);
                return null;
            });
        } catch (RuntimeException ex) {
            if (!containsMessage(ex, "BUSYGROUP")) {
                log.error("failed to create agent event ingress consumer group: group={}",
                        properties.getEventIngressConsumerGroup(), ex);
                throw ex;
            }
        }
    }

    @Scheduled(fixedDelayString = "${agent.control-plane.event-ingress-poll-interval-millis:1000}")
    public void poll() {
        claimPendingRecords().forEach(this::handleRecord);
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

    private List<MapRecord<String, String, String>> claimPendingRecords() {
        List<MapRecord<String, String, String>> claimed = stringRedisTemplate.execute(
                (RedisCallback<List<MapRecord<String, String, String>>>) connection -> {
                    PendingMessages pending = connection.streamCommands().xPending(
                            raw(AgentCoordinationKeys.eventIngressStream()),
                            properties.getEventIngressConsumerGroup(),
                            RedisStreamCommands.XPendingOptions.range(Range.unbounded(),
                                    (long) properties.getEventIngressClaimBatchSize()));
                    if (pending == null || pending.isEmpty()) {
                        return List.of();
                    }
                    List<RecordId> ids = new ArrayList<>();
                    pending.forEach(message -> {
                        if (message.getElapsedTimeSinceLastDelivery()
                                .compareTo(properties.getEventIngressPendingMinIdle()) >= 0) {
                            ids.add(message.getId());
                        }
                    });
                    if (ids.isEmpty()) {
                        return List.of();
                    }
                    List<ByteRecord> records = connection.streamCommands().xClaim(
                            raw(AgentCoordinationKeys.eventIngressStream()),
                            properties.getEventIngressConsumerGroup(),
                            consumerName(),
                            RedisStreamCommands.XClaimOptions.minIdle(properties.getEventIngressPendingMinIdle())
                                    .ids(ids));
                    if (records == null || records.isEmpty()) {
                        return List.of();
                    }
                    return records.stream().map(this::toStringRecord).toList();
                });
        return claimed == null ? List.of() : claimed;
    }

    void handleRecord(MapRecord<String, ?, ?> record) {
        String type = stringValue(record, AgentCoordinationKeys.EVENT_INGRESS_FIELD_TYPE);
        String payload = stringValue(record, AgentCoordinationKeys.EVENT_INGRESS_FIELD_PAYLOAD);
        Long oldTenantId = TenantContextHolder.getTenantId();
        try {
            if (AgentCoordinationKeys.EVENT_INGRESS_TYPE_AGENT_EVENT.equals(type)) {
                AgentEventIngressPayload ingressPayload = objectMapper.readValue(payload, AgentEventIngressPayload.class);
                setTenantContext(ingressPayload.tenantId());
                ingressService.handleAgentEvent(ingressPayload);
            } else if (AgentCoordinationKeys.EVENT_INGRESS_TYPE_COMMAND_ACK.equals(type)) {
                CommandAckIngressPayload ingressPayload = objectMapper.readValue(payload, CommandAckIngressPayload.class);
                setTenantContext(ingressPayload.tenantId());
                ingressService.handleCommandAck(ingressPayload);
            } else {
                log.warn("unknown agent event ingress stream type: recordId={}, type={}", record.getId(), type);
                return;
            }
            stringRedisTemplate.opsForStream().acknowledge(AgentCoordinationKeys.eventIngressStream(),
                    properties.getEventIngressConsumerGroup(), record.getId());
        } catch (Exception ex) {
            log.warn("failed to consume agent ingress stream record: recordId={}, type={}", record.getId(), type, ex);
        } finally {
            if (oldTenantId == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(oldTenantId);
            }
        }
    }

    private String stringValue(MapRecord<String, ?, ?> record, String key) {
        Object value = record.getValue().get(key);
        return value == null ? null : value.toString();
    }

    private void setTenantContext(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("agent ingress tenantId is required");
        }
        TenantContextHolder.setTenantId(tenantId);
    }

    private String consumerName() {
        return properties.getEventIngressConsumerNamePrefix() + consumerName;
    }

    private MapRecord<String, String, String> toStringRecord(ByteRecord record) {
        Map<String, String> values = new LinkedHashMap<>();
        record.getValue().forEach((key, value) -> values.put(text(key), text(value)));
        return StreamRecords.string(values)
                .withStreamKey(AgentCoordinationKeys.eventIngressStream())
                .withId(record.getId());
    }

    private byte[] raw(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String text(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private boolean containsMessage(Throwable throwable, String text) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
