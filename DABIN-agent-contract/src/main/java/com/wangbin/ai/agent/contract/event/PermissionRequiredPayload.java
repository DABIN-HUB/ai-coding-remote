package com.wangbin.ai.agent.contract.event;

import java.util.Map;

public record PermissionRequiredPayload(
        String permissionId,
        String title,
        String reason,
        Map<String, Object> request,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public PermissionRequiredPayload {
        request = request == null ? Map.of() : Map.copyOf(request);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
