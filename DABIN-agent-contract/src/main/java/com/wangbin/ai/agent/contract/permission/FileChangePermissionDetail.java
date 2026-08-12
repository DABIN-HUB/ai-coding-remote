package com.wangbin.ai.agent.contract.permission;

import com.wangbin.ai.agent.contract.enums.PermissionDecision;

import java.util.List;
import java.util.Map;

public record FileChangePermissionDetail(
        String itemId,
        String turnId,
        String reason,
        String grantRoot,
        List<FileChangeSummary> changes,
        List<PermissionDecision> availableDecisions,
        Map<String, Object> extensions
) implements PermissionRequestDetail {

    public FileChangePermissionDetail {
        changes = changes == null ? List.of() : List.copyOf(changes);
        availableDecisions = availableDecisions == null ? List.of() : List.copyOf(availableDecisions);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
