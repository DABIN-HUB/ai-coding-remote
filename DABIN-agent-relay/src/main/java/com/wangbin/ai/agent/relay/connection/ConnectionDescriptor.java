package com.wangbin.ai.agent.relay.connection;

import java.time.Instant;

public record ConnectionDescriptor(
        String connectionId,
        ConnectionRole role,
        String tenantId,
        String userId,
        String deviceId,
        Instant connectedAt
) {
}
