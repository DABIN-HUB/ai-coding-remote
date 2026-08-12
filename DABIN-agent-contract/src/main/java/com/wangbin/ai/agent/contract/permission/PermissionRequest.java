package com.wangbin.ai.agent.contract.permission;

import com.wangbin.ai.agent.contract.enums.PermissionType;

import java.util.Map;

public record PermissionRequest(
        String permissionId,
        PermissionType permissionType,
        String title,
        String reason,
        PermissionRequestDetail detail,
        Map<String, Object> extensions
) {

    public PermissionRequest {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
