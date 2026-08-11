package com.wangbin.ai.agent.relay.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.command.CommandAckStatus;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.AgentEventIngressPayload;
import com.wangbin.ai.agent.contract.coordination.CommandAckIngressPayload;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import com.wangbin.ai.agent.relay.connection.ConnectionDescriptor;
import com.wangbin.ai.agent.relay.connection.ConnectionRole;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventIngressPublisherTest {

    private static final Long TRUSTED_TENANT_ID = 1L;
    private static final Long TRUSTED_USER_ID = 11L;
    private static final String TRUSTED_DEVICE_ID = "dev-1";
    private static final String CONNECTION_ID = "conn-1";
    private static final String RELAY_NODE_ID = "relay-1";
    private static final String SESSION_ID = "ses-1";
    private static final String PROJECT_ID = "prj-1";
    private static final String COMMAND_ID = "cmd-1";

    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void publishedAgentEventIngressIdentityComesFromConnectionDescriptor() throws Exception {
        CapturingStringRedisTemplate redisTemplate = new CapturingStringRedisTemplate();
        AgentEventIngressPublisher publisher = new AgentEventIngressPublisher(redisTemplate, objectMapper,
                properties(), new AgentEventReliabilityPolicy());
        AgentEvent untrustedEvent = new AgentEvent("event-1", "trace-1", 999L, 888L, "evil-device",
                PROJECT_ID, SESSION_ID, 1L, AgentType.CODEX, AgentEventType.SESSION_IDLE, EventPriority.IMPORTANT,
                Instant.now(), new SessionPayload("native-1", AgentSessionStatus.IDLE, null, Map.of()), Map.of());

        publisher.publish(descriptor(), RELAY_NODE_ID, untrustedEvent).block(Duration.ofSeconds(1));

        AgentEventIngressPayload payload = objectMapper.readValue((String) redisTemplate.capturedFields()
                .get(AgentCoordinationKeys.EVENT_INGRESS_FIELD_PAYLOAD), AgentEventIngressPayload.class);
        assertThat(payload.tenantId()).isEqualTo(TRUSTED_TENANT_ID);
        assertThat(payload.userId()).isEqualTo(TRUSTED_USER_ID);
        assertThat(payload.deviceId()).isEqualTo(TRUSTED_DEVICE_ID);
        assertThat(payload.event().userId()).isEqualTo(888L);
        assertThat(payload.event().deviceId()).isEqualTo("evil-device");
    }

    @Test
    void publishedCommandAckIngressIdentityComesFromConnectionDescriptor() throws Exception {
        CapturingStringRedisTemplate redisTemplate = new CapturingStringRedisTemplate();
        AgentEventIngressPublisher publisher = new AgentEventIngressPublisher(redisTemplate, objectMapper,
                properties(), new AgentEventReliabilityPolicy());
        CommandAck untrustedAck = new CommandAck(COMMAND_ID, SESSION_ID, "evil-device",
                CommandAckStatus.ACCEPTED, "ACCEPTED", "accepted", Instant.now(), Map.of());

        publisher.publishAck(descriptor(), RELAY_NODE_ID, untrustedAck).block(Duration.ofSeconds(1));

        CommandAckIngressPayload payload = objectMapper.readValue((String) redisTemplate.capturedFields()
                .get(AgentCoordinationKeys.EVENT_INGRESS_FIELD_PAYLOAD), CommandAckIngressPayload.class);
        assertThat(payload.tenantId()).isEqualTo(TRUSTED_TENANT_ID);
        assertThat(payload.userId()).isEqualTo(TRUSTED_USER_ID);
        assertThat(payload.deviceId()).isEqualTo(TRUSTED_DEVICE_ID);
        assertThat(payload.ack().deviceId()).isEqualTo("evil-device");
    }

    private AgentRelayProperties properties() {
        AgentRelayProperties properties = new AgentRelayProperties();
        properties.setEventIngressStreamMaxLen(100);
        return properties;
    }

    private ConnectionDescriptor descriptor() {
        return new ConnectionDescriptor(CONNECTION_ID, ConnectionRole.DEVICE, TRUSTED_TENANT_ID,
                TRUSTED_USER_ID, TRUSTED_DEVICE_ID, Instant.now());
    }

    private static final class CapturingStringRedisTemplate extends StringRedisTemplate {

        private final AtomicReference<Map<?, ?>> capturedFields = new AtomicReference<>();

        @Override
        @SuppressWarnings("unchecked")
        public <HK, HV> StreamOperations<String, HK, HV> opsForStream() {
            return (StreamOperations<String, HK, HV>) Proxy.newProxyInstance(
                    StreamOperations.class.getClassLoader(),
                    new Class<?>[]{StreamOperations.class},
                    (proxy, method, args) -> {
                        if ("add".equals(method.getName()) && args != null && args.length >= 2
                                && args[1] instanceof Map<?, ?> fields) {
                            capturedFields.set(fields);
                            return RecordId.of("1-0");
                        }
                        if ("trim".equals(method.getName())) {
                            return 0L;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        private Map<?, ?> capturedFields() {
            return capturedFields.get();
        }
    }
}
