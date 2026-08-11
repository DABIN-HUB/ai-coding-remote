package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.wangbin.ai.agent.contract.enums.AgentType;

public record RegisterProjectRequest(
        String localProjectId,
        String projectName,
        String workspacePath,
        String workspaceRealPath,
        AgentType agentType
) {
}
