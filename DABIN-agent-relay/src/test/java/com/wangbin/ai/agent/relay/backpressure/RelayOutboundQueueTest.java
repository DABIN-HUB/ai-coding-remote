package com.wangbin.ai.agent.relay.backpressure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.contract.event.AgentErrorPayload;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import com.wangbin.ai.agent.relay.connection.ConnectionDescriptor;
import com.wangbin.ai.agent.relay.connection.ConnectionRegistration;
import com.wangbin.ai.agent.relay.connection.ConnectionRole;
import com.wangbin.ai.agent.relay.connection.InMemoryConnectionManager;
import com.wangbin.ai.agent.relay.dispatch.WebSocketEventDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayOutboundQueueTest {

    private static final int SINGLE_MESSAGE_CAPACITY = 1;
    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final String CONNECTION_A = "connection-a";
    private static final String CONNECTION_B = "connection-b";
    private static final String DEVICE_ID = "device-1";
    private static final String PROJECT_ID = "project-1";
    private static final String SESSION_ID = "session-1";
    private static final String TRACE_ID = "trace-1";
    private static final String ERROR_CODE = "protocol_error";
    private static final int EVENT_SEQUENCE = 1;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void createsIndependentOutboundQueuePerConnection() {
        InMemoryConnectionManager manager = connectionManager(SINGLE_MESSAGE_CAPACITY);
        manager.register(registration(CONNECTION_A, TEST_USER_ID)).block(Duration.ofSeconds(1));
        manager.register(registration(CONNECTION_B, TEST_USER_ID)).block(Duration.ofSeconds(1));

        var connectionA = manager.findByConnectionId(CONNECTION_A).orElseThrow();
        var connectionB = manager.findByConnectionId(CONNECTION_B).orElseThrow();
        assertThat(connectionA.enqueue(new OutboundMessage(CONNECTION_A, EventPriority.NORMAL, "a-only", null)))
                .isTrue();

        assertThat(connectionA.outboundChannel()).isNotSameAs(connectionB.outboundChannel());
        assertThat(connectionA.enqueue(new OutboundMessage(CONNECTION_A, EventPriority.NORMAL, "a-full", null)))
                .isFalse();
        assertThat(connectionB.enqueue(new OutboundMessage(CONNECTION_B, EventPriority.NORMAL, "b-only", null)))
                .isTrue();
    }

    @Test
    void dispatcherFailsExplicitlyWhenCriticalEventCannotBeQueued() {
        InMemoryConnectionManager manager = connectionManager(SINGLE_MESSAGE_CAPACITY);
        manager.register(registration(CONNECTION_A, TEST_USER_ID)).block(Duration.ofSeconds(1));
        var connection = manager.findByConnectionId(CONNECTION_A).orElseThrow();
        connection.enqueue(new OutboundMessage(CONNECTION_A, EventPriority.CRITICAL, "already-full", null));
        WebSocketEventDispatcher dispatcher = new WebSocketEventDispatcher(manager, objectMapper);

        assertThatThrownBy(() -> dispatcher.dispatchToUser(TEST_USER_ID, criticalEvent()).block(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("critical outbound message rejected");
    }

    private InMemoryConnectionManager connectionManager(int capacity) {
        AgentRelayProperties properties = new AgentRelayProperties();
        properties.setOutboundQueueCapacity(capacity);
        return new InMemoryConnectionManager(properties);
    }

    private ConnectionRegistration registration(String connectionId, Long userId) {
        return new ConnectionRegistration(new ConnectionDescriptor(connectionId, ConnectionRole.BROWSER,
                TEST_TENANT_ID, userId, null, Instant.now()), fakeSession(connectionId));
    }

    private WebSocketSession fakeSession(String connectionId) {
        return (WebSocketSession) Proxy.newProxyInstance(WebSocketSession.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> {
                    Class<?> returnType = method.getReturnType();
                    if (String.class.equals(returnType)) {
                        return connectionId;
                    }
                    if (boolean.class.equals(returnType)) {
                        return false;
                    }
                    if (Map.class.equals(returnType)) {
                        return Map.of();
                    }
                    if (Mono.class.equals(returnType)) {
                        return Mono.empty();
                    }
                    if (Flux.class.equals(returnType)) {
                        return Flux.empty();
                    }
                    return null;
                });
    }

    private AgentEvent criticalEvent() {
        return new AgentEvent(null, TRACE_ID, TEST_TENANT_ID, TEST_USER_ID, DEVICE_ID, PROJECT_ID,
                SESSION_ID, EVENT_SEQUENCE, AgentType.CODEX, AgentEventType.ERROR, null, null,
                new AgentErrorPayload(ERROR_CODE, "critical event", false, Map.of()), Map.of());
    }

}
