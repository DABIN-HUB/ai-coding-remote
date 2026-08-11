package com.wangbin.ai.agent.contract.coordination;

import java.time.Instant;

public record DeviceRoutePayload(
        String relayNodeId,
        String connectionId,
        Long tenantId,
        Long userId,
        String deviceId,
        Instant registeredAt,
        Instant lastSeenAt
) {
}
