package com.wangbin.ai.agent.daemon.project;

import com.wangbin.ai.agent.contract.enums.AgentType;

public record AuthorizedProjectState(
        String localProjectId,
        String projectName,
        String workspacePath,
        AgentType agentType
) {
}
