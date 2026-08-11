package com.wangbin.ai.agent.relay.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.relay.backpressure.OutboundMessage;
import com.wangbin.ai.agent.relay.connection.ConnectionContext;
import com.wangbin.ai.agent.relay.connection.ConnectionManager;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.stereotype.Component;
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
        return connectionManager.findDeviceConnection(deviceId)
                .map(context -> enqueueAndDrain(context, event))
                .orElseGet(Mono::empty);
    }

    @Override
    public Mono<Void> dispatchToUser(Long userId, AgentEvent event) {
        return Flux.fromIterable(connectionManager.findUserConnections(userId))
                .flatMap(context -> enqueueAndDrain(context, event))
                .then();
    }

    private Mono<Void> enqueueAndDrain(ConnectionContext context, AgentEvent event) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(event))
                .flatMap(payload -> {
                    boolean accepted = context.enqueue(new OutboundMessage(
                            context.descriptor().connectionId(), event.priority(), payload, null));
                    if (!accepted && (event.priority() == EventPriority.CRITICAL
                            || event.priority() == EventPriority.IMPORTANT)) {
                        if (event.priority() == EventPriority.CRITICAL) {
                            context.markClosed();
                            return context.session().close(CloseStatus.SERVER_ERROR)
                                    .then(Mono.<Void>error(new IllegalStateException("critical outbound message rejected: "
                                            + context.descriptor().connectionId())));
                        }
                        return Mono.<Void>error(new IllegalStateException("reliable outbound message rejected: "
                                + context.descriptor().connectionId()));
                    }
                    return Mono.<Void>empty();
                })
                .onErrorMap(JsonProcessingException.class,
                        ex -> new IllegalStateException("failed to serialize AgentEvent", ex));
    }

}
