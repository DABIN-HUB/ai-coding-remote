package com.wangbin.ai.agent.contract.event;

import java.util.Map;

public record FileChangedPayload(
        String path,
        String changeType,
        String summary,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public FileChangedPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
