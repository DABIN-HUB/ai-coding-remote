package com.wangbin.ai.agent.daemon.cloud.runner;

import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.runtime.AgentRuntimeTypes;
import com.wangbin.ai.agent.contract.session.AgentCapabilities;
import com.wangbin.ai.agent.daemon.adapter.CodingAgentAdapter;
import com.wangbin.ai.agent.daemon.cloud.controlplane.ControlPlaneClient;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RegisterProjectRequest;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RegisterProjectResponse;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RuntimeReportRequest;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.exception.AgentProtocolException;
import com.wangbin.ai.agent.daemon.project.AuthorizedProjectState;
import com.wangbin.ai.agent.daemon.project.AuthorizedProjectStore;
import com.wangbin.ai.agent.daemon.project.LocalProjectRegistry;
import com.wangbin.ai.agent.daemon.project.LocalProjectIdFactory;
import com.wangbin.ai.agent.daemon.runtime.RuntimeDiscovery;
import com.wangbin.ai.agent.daemon.runtime.RuntimeDiscoveryResult;
import com.wangbin.ai.agent.daemon.runtime.RuntimeInstallStatus;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Performs local-authority bootstrap before the daemon joins Relay. Cloud data
 * is never used as the local execution workspace; the registry is populated
 * only from locally validated real paths.
 */
@Component
public class DaemonProjectRuntimeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DaemonProjectRuntimeBootstrap.class);

    private final AgentDaemonProperties properties;
    private final AuthorizedProjectStore authorizedProjectStore;
    private final WorkspaceManager workspaceManager;
    private final ControlPlaneClient controlPlaneClient;
    private final LocalProjectRegistry localProjectRegistry;
    private final RuntimeDiscovery runtimeDiscovery;
    private final List<CodingAgentAdapter> adapters;

    public DaemonProjectRuntimeBootstrap(AgentDaemonProperties properties,
                                         AuthorizedProjectStore authorizedProjectStore,
                                         WorkspaceManager workspaceManager,
                                         ControlPlaneClient controlPlaneClient,
                                         LocalProjectRegistry localProjectRegistry,
                                         RuntimeDiscovery runtimeDiscovery,
                                         List<CodingAgentAdapter> adapters) {
        this.properties = properties;
        this.authorizedProjectStore = authorizedProjectStore;
        this.workspaceManager = workspaceManager;
        this.controlPlaneClient = controlPlaneClient;
        this.localProjectRegistry = localProjectRegistry;
        this.runtimeDiscovery = runtimeDiscovery;
        this.adapters = adapters;
    }

    public void bootstrap(DeviceCredentialState credential) {
        registerProjects(credential);
        reportCodexRuntime(credential);
    }

    private void registerProjects(DeviceCredentialState credential) {
        List<AuthorizedProjectState> projects = loadAuthorizedProjects();
        for (AuthorizedProjectState project : projects) {
            try {
                registerProject(credential, project);
            } catch (RuntimeException ex) {
                log.warn("skip unavailable local project: localProjectId={}, workspacePath={}, reason={}",
                        project.localProjectId(), project.workspacePath(), ex.getMessage());
            }
        }
    }

    private List<AuthorizedProjectState> loadAuthorizedProjects() {
        List<AuthorizedProjectState> projects = authorizedProjectStore.load();
        if (!projects.isEmpty()) {
            return projects;
        }
        List<AuthorizedProjectState> imported = importConfiguredProjects();
        if (!imported.isEmpty()) {
            return authorizedProjectStore.addProjects(imported);
        }
        return List.of();
    }

    private List<AuthorizedProjectState> importConfiguredProjects() {
        List<AuthorizedProjectState> imported = new ArrayList<>();
        for (AgentDaemonProperties.Project configured : properties.getProjects()) {
            try {
                Path realWorkspace = workspaceManager.validateWorkspace(configured.getWorkspacePath());
                AgentType agentType = configured.getAgentType() == null ? AgentType.CODEX : configured.getAgentType();
                String localProjectId = textOrDefault(configured.getLocalProjectId(),
                        LocalProjectIdFactory.stableLocalProjectId(realWorkspace));
                String projectName = textOrDefault(configured.getProjectName(), defaultProjectName(realWorkspace));
                imported.add(new AuthorizedProjectState(localProjectId, projectName, realWorkspace.toString(),
                        agentType));
            } catch (RuntimeException ex) {
                log.warn("skip invalid legacy configured project: workspacePath={}, reason={}",
                        configured.getWorkspacePath(), ex.getMessage());
            }
        }
        return imported;
    }

    private void registerProject(DeviceCredentialState credential, AuthorizedProjectState project) {
        Path realWorkspace = workspaceManager.validateWorkspace(project.workspacePath());
        AgentType agentType = project.agentType() == null ? AgentType.CODEX : project.agentType();
        String localProjectId = textOrDefault(project.localProjectId(),
                LocalProjectIdFactory.stableLocalProjectId(realWorkspace));
        String projectName = textOrDefault(project.projectName(), defaultProjectName(realWorkspace));
        RegisterProjectResponse response = controlPlaneClient.registerProject(credential,
                new RegisterProjectRequest(localProjectId, projectName, project.workspacePath(),
                        realWorkspace.toString(), agentType));
        String platformProjectId = response == null ? null : response.projectId();
        if (platformProjectId == null || platformProjectId.isBlank()) {
            throw new AgentProtocolException("control plane did not return platform project id");
        }
        localProjectRegistry.register(platformProjectId, localProjectId, projectName, realWorkspace.toString(),
                agentType);
    }

    private void reportCodexRuntime(DeviceCredentialState credential) {
        RuntimeDiscoveryResult result = runtimeDiscovery.discover(AgentType.CODEX);
        if (result.status() != RuntimeInstallStatus.INSTALLED) {
            log.warn("Codex runtime is not available locally: status={}, diagnostic={}",
                    result.status(), result.diagnostic());
            return;
        }
        try {
            controlPlaneClient.reportRuntime(credential, new RuntimeReportRequest(null, AgentType.CODEX,
                    AgentRuntimeTypes.CODEX_APP_SERVER, result.version(), executablePath(result),
                    capabilities(AgentType.CODEX)));
        } catch (RuntimeException ex) {
            log.warn("failed to report local runtime; daemon will keep relay reconnect loop alive: runtimeType={}, reason={}",
                    AgentRuntimeTypes.CODEX_APP_SERVER, ex.getMessage());
        }
    }

    private AgentCapabilities capabilities(AgentType agentType) {
        return adapters.stream()
                .filter(adapter -> adapter.agentType() == agentType)
                .findFirst()
                .map(CodingAgentAdapter::capabilities)
                .orElseGet(AgentCapabilities::unknown);
    }

    private String executablePath(RuntimeDiscoveryResult result) {
        if (result.resolvedPath() != null) {
            return result.resolvedPath().toString();
        }
        return result.executable();
    }

    private String defaultProjectName(Path realWorkspace) {
        Path fileName = realWorkspace.getFileName();
        return fileName == null ? realWorkspace.toString() : fileName.toString();
    }

    private String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
