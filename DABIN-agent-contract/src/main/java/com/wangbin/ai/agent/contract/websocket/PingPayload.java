package com.wangbin.ai.agent.contract.websocket;

import java.time.Instant;

public record PingPayload(
        String pingId,
        Instant serverTime
) {
}
