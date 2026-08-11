package com.wangbin.ai.agent.contract.coordination;

import com.wangbin.ai.agent.contract.command.CommandAck;

import java.time.Instant;

public record CommandAckIngressPayload(
        String relayNodeId,
        String connectionId,
        Long tenantId,
        Long userId,
        String deviceId,
        CommandAck ack,
        Instant receivedAt
) {

    public CommandAckIngressPayload {
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
    }

    public CommandAckIngressPayload(String relayNodeId, String connectionId, CommandAck ack, Instant receivedAt) {
        this(relayNodeId, connectionId, null, null, ack == null ? null : ack.deviceId(), ack, receivedAt);
    }
}
