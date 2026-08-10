package com.wangbin.ai.agent.relay.routing;

import java.time.Instant;

public record RouteMetadata(
        String deviceId,
        String relayNodeId,
        Instant updatedAt
) {
}
