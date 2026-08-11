package com.wangbin.ai.agent.contract.coordination;

import com.wangbin.ai.agent.contract.command.AgentCommand;

import java.time.Instant;

public record RelayCommandDispatchPayload(
        String targetRelayNodeId,
        String targetDeviceId,
        String targetConnectionId,
        Long tenantId,
        AgentCommand command,
        Instant dispatchedAt
) {

    public RelayCommandDispatchPayload {
        dispatchedAt = dispatchedAt == null ? Instant.now() : dispatchedAt;
    }
}
