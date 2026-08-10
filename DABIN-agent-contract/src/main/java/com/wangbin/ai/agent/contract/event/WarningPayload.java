package com.wangbin.ai.agent.contract.event;

import java.util.Map;

public record WarningPayload(
        String message,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public WarningPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
