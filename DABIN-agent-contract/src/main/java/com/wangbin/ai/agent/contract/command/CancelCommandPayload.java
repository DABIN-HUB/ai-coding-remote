package com.wangbin.ai.agent.contract.command;

import java.util.Map;

/**
 * Requests daemon to stop a user prompt command. The prompt reaches CANCELLED only after native terminal lifecycle.
 */
public record CancelCommandPayload(
        String targetCommandId,
        String reason,
        Map<String, Object> extensions
) implements AgentCommandPayload {

    public CancelCommandPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
