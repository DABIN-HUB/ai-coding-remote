package com.wangbin.ai.agent.contract.coordination;

import java.time.Instant;

public record UserRoutePayload(
        String relayNodeId,
        String connectionId,
        Long tenantId,
        Long userId,
        Instant registeredAt,
        Instant lastSeenAt
) {
}
