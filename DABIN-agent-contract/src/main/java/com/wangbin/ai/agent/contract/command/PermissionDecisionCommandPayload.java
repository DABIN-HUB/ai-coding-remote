package com.wangbin.ai.agent.contract.command;

import com.wangbin.ai.agent.contract.enums.PermissionDecision;

import java.util.Map;

public record PermissionDecisionCommandPayload(
        String permissionId,
        PermissionDecision decision,
        String reason,
        Map<String, Object> extensions
) implements AgentCommandPayload {

    public PermissionDecisionCommandPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
