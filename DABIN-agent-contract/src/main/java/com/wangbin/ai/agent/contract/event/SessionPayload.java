package com.wangbin.ai.agent.contract.event;

import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;

import java.util.Map;

public record SessionPayload(
        String nativeSessionId,
        AgentSessionStatus status,
        String reason,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public SessionPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
