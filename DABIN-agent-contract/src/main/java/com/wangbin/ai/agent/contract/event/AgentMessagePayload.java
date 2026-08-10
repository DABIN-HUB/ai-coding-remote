package com.wangbin.ai.agent.contract.event;

import java.util.Map;

public record AgentMessagePayload(
        String messageId,
        String role,
        String content,
        boolean delta,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public AgentMessagePayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
