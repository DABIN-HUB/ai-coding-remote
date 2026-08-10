package com.wangbin.ai.agent.contract.event;

import java.util.Map;

public record AgentErrorPayload(
        String code,
        String message,
        boolean retryable,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public AgentErrorPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
