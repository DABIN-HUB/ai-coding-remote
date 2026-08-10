package com.wangbin.ai.agent.relay.backpressure;

import com.wangbin.ai.agent.contract.enums.EventPriority;

import java.time.Instant;

public record OutboundMessage(
        String connectionId,
        EventPriority priority,
        String payload,
        Instant createdAt
) {

    public OutboundMessage {
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

}
