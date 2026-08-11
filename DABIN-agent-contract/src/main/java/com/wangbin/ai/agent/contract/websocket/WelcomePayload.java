package com.wangbin.ai.agent.contract.websocket;

import java.time.Duration;
import java.time.Instant;

public record WelcomePayload(
        String connectionId,
        String relayNodeId,
        Duration heartbeatInterval,
        Instant serverTime
) {
}
