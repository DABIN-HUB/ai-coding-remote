package com.wangbin.ai.agent.contract.coordination;

import com.wangbin.ai.agent.contract.event.AgentEvent;

import java.time.Instant;

public record AgentEventIngressPayload(
        String relayNodeId,
        String connectionId,
        Long tenantId,
        Long userId,
        String deviceId,
        AgentEvent event,
        Instant receivedAt
) {

    public AgentEventIngressPayload {
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
    }

    public AgentEventIngressPayload(String relayNodeId, String connectionId, AgentEvent event, Instant receivedAt) {
        this(relayNodeId, connectionId, event == null ? null : event.tenantId(),
                event == null ? null : event.userId(), event == null ? null : event.deviceId(), event, receivedAt);
    }
}
