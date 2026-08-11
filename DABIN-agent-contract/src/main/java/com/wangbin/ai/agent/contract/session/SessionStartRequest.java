package com.wangbin.ai.agent.contract.session;

import com.wangbin.ai.agent.contract.enums.AgentType;

import java.util.Map;

public record SessionStartRequest(
        String platformSessionId,
        Long tenantId,
        Long userId,
        String deviceId,
        String projectId,
        String workspacePath,
        AgentType agentType,
        Map<String, Object> metadata
) {

    public SessionStartRequest {
        agentType = agentType == null ? AgentType.CODEX : agentType;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

}
