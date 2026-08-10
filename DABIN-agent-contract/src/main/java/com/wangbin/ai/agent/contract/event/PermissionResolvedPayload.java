package com.wangbin.ai.agent.contract.event;

import com.wangbin.ai.agent.contract.enums.PermissionDecision;

import java.util.Map;

public record PermissionResolvedPayload(
        String permissionId,
        PermissionDecision decision,
        String reason,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public PermissionResolvedPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
