package com.wangbin.ai.agent.contract.event;

import com.wangbin.ai.agent.contract.enums.PermissionType;
import com.wangbin.ai.agent.contract.permission.PermissionRequestDetail;

import java.util.Map;

public record PermissionRequiredPayload(
        String permissionId,
        PermissionType permissionType,
        String title,
        String reason,
        PermissionRequestDetail detail,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public PermissionRequiredPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
