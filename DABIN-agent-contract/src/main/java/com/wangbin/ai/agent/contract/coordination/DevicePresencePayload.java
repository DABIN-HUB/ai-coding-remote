package com.wangbin.ai.agent.contract.coordination;

import java.time.Instant;

public record DevicePresencePayload(
        String relayNodeId,
        String connectionId,
        Long tenantId,
        Long userId,
        String deviceId,
        Instant lastSeenAt
) {
}
