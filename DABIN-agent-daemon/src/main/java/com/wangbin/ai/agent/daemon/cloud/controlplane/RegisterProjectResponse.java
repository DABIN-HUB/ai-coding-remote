package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
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
