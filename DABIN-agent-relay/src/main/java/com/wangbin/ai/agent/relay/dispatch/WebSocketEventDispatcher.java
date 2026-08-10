package com.wangbin.ai.agent.relay.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.relay.connection.ConnectionManager;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class WebSocketEventDispatcher implements EventDispatcher {

    private final ConnectionManager connectionManager;
    private final ObjectMapper objectMapper;

    public WebSocketEventDispatcher(ConnectionManager connectionManager, ObjectMapper objectMapper) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> dispatchToDevice(String deviceId, AgentEvent event) {
        return connectionManager.findDeviceSession(deviceId)
                .map(session -> send(session, event))
                .orElseGet(Mono::empty);
    }

    @Override
    public Mono<Void> dispatchToUser(String userId, AgentEvent event) {
        return Flux.fromIterable(connectionManager.findUserSessions(userId))
                .flatMap(session -> send(session, event))
                .then();
    }

    private Mono<Void> send(WebSocketSession session, AgentEvent event) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(event))
                .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                .onErrorMap(JsonProcessingException.class,
                        ex -> new IllegalStateException("failed to serialize AgentEvent", ex));
    }

}
