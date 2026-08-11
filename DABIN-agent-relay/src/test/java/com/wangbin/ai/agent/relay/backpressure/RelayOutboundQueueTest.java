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

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void createsIndependentOutboundQueuePerConnection() {
        InMemoryConnectionManager manager = connectionManager(1);
        manager.register(registration("connection-a", 11L)).block(Duration.ofSeconds(1));
        manager.register(registration("connection-b", 11L)).block(Duration.ofSeconds(1));

        var connectionA = manager.findByConnectionId("connection-a").orElseThrow();
        var connectionB = manager.findByConnectionId("connection-b").orElseThrow();
        assertThat(connectionA.enqueue(new OutboundMessage("connection-a", EventPriority.NORMAL, "a-only", null)))
                .isTrue();

        assertThat(connectionA.outboundChannel()).isNotSameAs(connectionB.outboundChannel());
        assertThat(connectionA.enqueue(new OutboundMessage("connection-a", EventPriority.NORMAL, "a-full", null)))
                .isFalse();
        assertThat(connectionB.enqueue(new OutboundMessage("connection-b", EventPriority.NORMAL, "b-only", null)))
                .isTrue();
    }

    @Test
    void dispatcherFailsExplicitlyWhenCriticalEventCannotBeQueued() {
        InMemoryConnectionManager manager = connectionManager(1);
        manager.register(registration("connection-a", 11L)).block(Duration.ofSeconds(1));
        var connection = manager.findByConnectionId("connection-a").orElseThrow();
        connection.enqueue(new OutboundMessage("connection-a", EventPriority.CRITICAL, "already-full", null));
        WebSocketEventDispatcher dispatcher = new WebSocketEventDispatcher(manager, objectMapper);

        assertThatThrownBy(() -> dispatcher.dispatchToUser(11L, criticalEvent()).block(Duration.ofSeconds(1)))
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
                1L, userId, null, Instant.now()), fakeSession(connectionId));
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
        return new AgentEvent(null, "trace-1", 1L, 11L, "device-1", "project-1",
                "session-1", 1, AgentType.CODEX, AgentEventType.ERROR, null, null,
                new AgentErrorPayload("protocol_error", "critical event", false, Map.of()), Map.of());
    }

}
