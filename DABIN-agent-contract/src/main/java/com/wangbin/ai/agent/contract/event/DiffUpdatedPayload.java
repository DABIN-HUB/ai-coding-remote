package com.wangbin.ai.agent.contract.event;

import java.util.Map;

public record DiffUpdatedPayload(
        String diff,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public DiffUpdatedPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
