package com.wangbin.ai.agent.contract.websocket;

import java.time.Instant;

public record PongPayload(
        String pingId,
        Instant clientTime
) {
}
