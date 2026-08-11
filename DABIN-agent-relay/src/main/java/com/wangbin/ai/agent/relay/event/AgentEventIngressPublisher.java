package com.wangbin.ai.agent.relay.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.AgentEventIngressPayload;
import com.wangbin.ai.agent.contract.coordination.CommandAckIngressPayload;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import com.wangbin.ai.agent.relay.connection.ConnectionDescriptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@Component
public class AgentEventIngressPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentRelayProperties properties;
    private final AgentEventReliabilityPolicy reliabilityPolicy;

    public AgentEventIngressPublisher(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper,
                                      AgentRelayProperties properties,
                                      AgentEventReliabilityPolicy reliabilityPolicy) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.reliabilityPolicy = reliabilityPolicy;
    }

    public Mono<Void> publish(ConnectionDescriptor descriptor, String relayNodeId, AgentEvent event) {
        if (!reliabilityPolicy.shouldPublishDurably(event)) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
            try {
                AgentEventIngressPayload payload = new AgentEventIngressPayload(relayNodeId,
                        descriptor.connectionId(), event, null);
                String json = objectMapper.writeValueAsString(payload);
                String key = AgentCoordinationKeys.eventIngressStream();
                stringRedisTemplate.opsForStream().add(key, Map.of(AgentCoordinationKeys.EVENT_INGRESS_FIELD_TYPE,
                        AgentCoordinationKeys.EVENT_INGRESS_TYPE_AGENT_EVENT,
                        AgentCoordinationKeys.EVENT_INGRESS_FIELD_PAYLOAD, json));
                stringRedisTemplate.opsForStream().trim(key, properties.getEventIngressStreamMaxLen());
            } catch (Exception ex) {
                throw new IllegalStateException("failed to publish AgentEvent ingress", ex);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> publishAck(ConnectionDescriptor descriptor, String relayNodeId, CommandAck ack) {
        return Mono.fromRunnable(() -> {
            try {
                CommandAckIngressPayload payload = new CommandAckIngressPayload(relayNodeId,
                        descriptor.connectionId(), ack, null);
                String json = objectMapper.writeValueAsString(payload);
                String key = AgentCoordinationKeys.eventIngressStream();
                stringRedisTemplate.opsForStream().add(key, Map.of(AgentCoordinationKeys.EVENT_INGRESS_FIELD_TYPE,
                        AgentCoordinationKeys.EVENT_INGRESS_TYPE_COMMAND_ACK,
                        AgentCoordinationKeys.EVENT_INGRESS_FIELD_PAYLOAD, json));
                stringRedisTemplate.opsForStream().trim(key, properties.getEventIngressStreamMaxLen());
            } catch (Exception ex) {
                throw new IllegalStateException("failed to publish CommandAck ingress", ex);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
