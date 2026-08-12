package com.wangbin.ai.agent.contract.command;

import java.util.Map;

/**
 * Requests daemon to close the platform session after safely stopping any active turn.
 */
public record CloseSessionCommandPayload(
        String targetCommandId,
        String reason,
        Map<String, Object> extensions
) implements AgentCommandPayload {

    public CloseSessionCommandPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
