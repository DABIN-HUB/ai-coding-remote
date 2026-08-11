package com.wangbin.ai.agent.contract.websocket;

import com.wangbin.ai.agent.contract.protocol.AgentProtocol;

import java.time.Instant;
import java.util.UUID;

public record WsEnvelope<T>(
        String messageId,
        WsMessageType type,
        String protocolVersion,
        Instant timestamp,
        T payload
) {

    public WsEnvelope {
        messageId = messageId == null || messageId.isBlank() ? UUID.randomUUID().toString() : messageId;
        protocolVersion = protocolVersion == null ? AgentProtocol.VERSION : protocolVersion;
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public static <T> WsEnvelope<T> of(WsMessageType type, T payload) {
        return new WsEnvelope<>(null, type, AgentProtocol.VERSION, null, payload);
    }
}
