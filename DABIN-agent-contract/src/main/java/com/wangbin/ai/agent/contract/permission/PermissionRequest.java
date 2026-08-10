package com.wangbin.ai.agent.contract.permission;

import java.util.Map;

public record PermissionRequest(
        String permissionId,
        String title,
        String reason,
        Map<String, Object> nativeRequest,
        Map<String, Object> extensions
) {

    public PermissionRequest {
        nativeRequest = nativeRequest == null ? Map.of() : Map.copyOf(nativeRequest);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
