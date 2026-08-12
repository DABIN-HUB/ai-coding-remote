package com.wangbin.ai.agent.contract.command;

import java.util.Map;

/**
 * Requests daemon to interrupt the currently active native turn for a known platform command.
 */
public record InterruptCommandPayload(
        String targetCommandId,
        String reason,
        Map<String, Object> extensions
) implements AgentCommandPayload {

    public InterruptCommandPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
