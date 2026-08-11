package com.wangbin.ai.agent.daemon.project;

import com.wangbin.ai.agent.contract.enums.AgentType;

import java.nio.file.Path;

public record LocalProject(
        String platformProjectId,
        String localProjectId,
        String projectName,
        Path realWorkspace,
        AgentType agentType
) {
}
