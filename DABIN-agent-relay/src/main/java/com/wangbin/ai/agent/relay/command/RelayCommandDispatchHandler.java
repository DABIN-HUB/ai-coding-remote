package com.wangbin.ai.agent.relay.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.RelayCommandDispatchPayload;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.contract.websocket.WsEnvelope;
import com.wangbin.ai.agent.contract.websocket.WsMessageType;
import com.wangbin.ai.agent.relay.backpressure.OutboundMessage;
import com.wangbin.ai.agent.relay.connection.ConnectionContext;
import com.wangbin.ai.agent.relay.connection.ConnectionManager;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RelayCommandDispatchHandler {

    private final ConnectionManager connectionManager;
    private final ObjectMapper objectMapper;

    public RelayCommandDispatchHandler(ConnectionManager connectionManager, ObjectMapper objectMapper) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> dispatch(RelayCommandDispatchPayload payload) {
        return connectionManager.findDeviceConnection(payload.targetDeviceId())
                .filter(context -> canDispatch(context, payload))
                .map(context -> enqueue(context, payload))
                .orElseGet(Mono::empty);
    }

    private boolean canDispatch(ConnectionContext context, RelayCommandDispatchPayload payload) {
        return context.descriptor().tenantId().equals(payload.tenantId())
                && context.descriptor().deviceId().equals(payload.targetDeviceId())
                && context.descriptor().connectionId().equals(payload.targetConnectionId());
    }

    private Mono<Void> enqueue(ConnectionContext context, RelayCommandDispatchPayload payload) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(WsEnvelope.of(WsMessageType.AGENT_COMMAND,
                        payload.command())))
                .flatMap(json -> {
                    boolean accepted = context.enqueue(new OutboundMessage(context.descriptor().connectionId(),
                            EventPriority.CRITICAL, json, null));
                    if (!accepted) {
                        return Mono.error(new IllegalStateException("command outbound queue rejected critical command"));
                    }
                    return Mono.empty();
                });
    }
}
