package com.wangbin.ai.agent.contract.event;

import java.util.Map;

public record TaskCompletedPayload(
        String taskId,
        String result,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public TaskCompletedPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
