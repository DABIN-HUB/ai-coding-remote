package com.wangbin.ai.agent.contract.event;

import java.util.Map;

public record ToolEventPayload(
        String toolCallId,
        String toolName,
        String status,
        String summary,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public ToolEventPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
