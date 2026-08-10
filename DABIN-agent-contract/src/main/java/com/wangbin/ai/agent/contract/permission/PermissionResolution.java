package com.wangbin.ai.agent.contract.permission;

import com.wangbin.ai.agent.contract.enums.PermissionDecision;

import java.time.Instant;
import java.util.Map;

public record PermissionResolution(
        String permissionId,
        PermissionDecision decision,
        String reason,
        Instant resolvedAt,
        Map<String, Object> extensions
) {

    public PermissionResolution {
        resolvedAt = resolvedAt == null ? Instant.now() : resolvedAt;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
