package com.wangbin.ai.agent.contract.event;

import com.wangbin.ai.agent.contract.enums.SessionControlAction;

import java.time.Instant;
import java.util.Map;

/**
 * Durable event emitted when a session control command was accepted by the daemon but no native terminal event
 * arrived within the configured terminal timeout.
 */
public record SessionControlTimeoutPayload(
        String targetCommandId,
        String controlCommandId,
        SessionControlAction action,
        Instant timeoutAt,
        String reason,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public SessionControlTimeoutPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
