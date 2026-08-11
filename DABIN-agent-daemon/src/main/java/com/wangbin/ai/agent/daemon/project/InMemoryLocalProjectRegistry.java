package com.wangbin.ai.agent.daemon.project;

import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryLocalProjectRegistry implements LocalProjectRegistry {

    private final WorkspaceManager workspaceManager;
    private final ConcurrentMap<String, LocalProject> projects = new ConcurrentHashMap<>();

    public InMemoryLocalProjectRegistry(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    @Override
    public LocalProject register(String platformProjectId, String localProjectId, String projectName,
                                 String workspacePath, AgentType agentType) {
        Path realWorkspace = workspaceManager.validateWorkspace(workspacePath);
        LocalProject project = new LocalProject(platformProjectId, localProjectId, projectName, realWorkspace,
                agentType == null ? AgentType.UNKNOWN : agentType);
        projects.put(platformProjectId, project);
        return project;
    }

    @Override
    public Optional<LocalProject> findByPlatformProjectId(String platformProjectId) {
        return Optional.ofNullable(projects.get(platformProjectId));
    }
}
