package com.wangbin.ai.agent.contract.permission;

import com.wangbin.ai.agent.contract.enums.PermissionDecision;

import java.util.List;
import java.util.Map;

public record CommandExecutionPermissionDetail(
        String itemId,
        String turnId,
        String command,
        String cwd,
        String reason,
        String environmentId,
        List<PermissionDecision> availableDecisions,
        Map<String, Object> extensions
) implements PermissionRequestDetail {

    public CommandExecutionPermissionDetail {
        availableDecisions = availableDecisions == null ? List.of() : List.copyOf(availableDecisions);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
