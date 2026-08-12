package com.wangbin.ai.agent.contract.event;

import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.enums.PermissionResolutionStatus;
import com.wangbin.ai.agent.contract.enums.PermissionType;

import java.time.Instant;
import java.util.Map;

public record PermissionResolvedPayload(
        String permissionId,
        PermissionType permissionType,
        PermissionDecision decision,
        PermissionResolutionStatus resolutionStatus,
        String decisionCommandId,
        Instant resolvedAt,
        String reason,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public PermissionResolvedPayload {
        resolvedAt = resolvedAt == null ? Instant.now() : resolvedAt;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
