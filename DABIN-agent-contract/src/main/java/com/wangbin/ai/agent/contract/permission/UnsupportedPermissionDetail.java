package com.wangbin.ai.agent.contract.permission;

import com.wangbin.ai.agent.contract.enums.PermissionDecision;

import java.util.List;
import java.util.Map;

public record UnsupportedPermissionDetail(
        String nativeMethod,
        String itemId,
        String turnId,
        String reason,
        List<PermissionDecision> availableDecisions,
        Map<String, Object> extensions
) implements PermissionRequestDetail {

    public UnsupportedPermissionDetail {
        availableDecisions = availableDecisions == null ? List.of() : List.copyOf(availableDecisions);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
