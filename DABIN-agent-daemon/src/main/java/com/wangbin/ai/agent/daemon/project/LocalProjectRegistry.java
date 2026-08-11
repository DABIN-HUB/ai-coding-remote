package com.wangbin.ai.agent.daemon.project;

import com.wangbin.ai.agent.contract.enums.AgentType;

import java.util.Optional;

public interface LocalProjectRegistry {

    LocalProject register(String platformProjectId, String localProjectId, String projectName, String workspacePath,
                          AgentType agentType);

    Optional<LocalProject> findByPlatformProjectId(String platformProjectId);
}
