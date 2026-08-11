package com.wangbin.ai.agent.daemon.cloud.controlplane;

public record RegisterProjectResponse(
        Long id,
        String projectId,
        String localProjectId,
        String projectName,
        String workspacePath,
        String workspaceRealPath,
        String agentType
) {
}
