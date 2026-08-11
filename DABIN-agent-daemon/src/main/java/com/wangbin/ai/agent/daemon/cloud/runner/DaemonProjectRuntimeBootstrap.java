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
import com.wangbin.ai.agent.daemon.project.LocalProjectRegistry;
import com.wangbin.ai.agent.daemon.runtime.RuntimeDiscovery;
import com.wangbin.ai.agent.daemon.runtime.RuntimeDiscoveryResult;
import com.wangbin.ai.agent.daemon.runtime.RuntimeInstallStatus;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * Performs local-authority bootstrap before the daemon joins Relay. Cloud data
 * is never used as the local execution workspace; the registry is populated
 * only from locally validated real paths.
 */
@Component
public class DaemonProjectRuntimeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DaemonProjectRuntimeBootstrap.class);
    private static final String LOCAL_PROJECT_ID_PREFIX = "local_";
    private static final String SHA_256_ALGORITHM = "SHA-256";

    private final AgentDaemonProperties properties;
    private final WorkspaceManager workspaceManager;
    private final ControlPlaneClient controlPlaneClient;
    private final LocalProjectRegistry localProjectRegistry;
    private final RuntimeDiscovery runtimeDiscovery;
    private final List<CodingAgentAdapter> adapters;

    public DaemonProjectRuntimeBootstrap(AgentDaemonProperties properties,
                                         WorkspaceManager workspaceManager,
                                         ControlPlaneClient controlPlaneClient,
                                         LocalProjectRegistry localProjectRegistry,
                                         RuntimeDiscovery runtimeDiscovery,
                                         List<CodingAgentAdapter> adapters) {
        this.properties = properties;
        this.workspaceManager = workspaceManager;
        this.controlPlaneClient = controlPlaneClient;
        this.localProjectRegistry = localProjectRegistry;
        this.runtimeDiscovery = runtimeDiscovery;
        this.adapters = adapters;
    }

    public void bootstrap(DeviceCredentialState credential) {
        registerConfiguredProjects(credential);
        reportCodexRuntime(credential);
    }

    private void registerConfiguredProjects(DeviceCredentialState credential) {
        for (AgentDaemonProperties.Project configured : properties.getProjects()) {
            Path realWorkspace = workspaceManager.validateWorkspace(configured.getWorkspacePath());
            AgentType agentType = configured.getAgentType() == null ? AgentType.CODEX : configured.getAgentType();
            String localProjectId = textOrDefault(configured.getLocalProjectId(), stableLocalProjectId(realWorkspace));
            String projectName = textOrDefault(configured.getProjectName(), defaultProjectName(realWorkspace));
            RegisterProjectResponse response = controlPlaneClient.registerProject(credential,
                    new RegisterProjectRequest(localProjectId, projectName, configured.getWorkspacePath(),
                            realWorkspace.toString(), agentType));
            String platformProjectId = response == null ? null : response.projectId();
            if (platformProjectId == null || platformProjectId.isBlank()) {
                throw new AgentProtocolException("control plane did not return platform project id");
            }
            localProjectRegistry.register(platformProjectId, localProjectId, projectName, realWorkspace.toString(),
                    agentType);
        }
    }

    private void reportCodexRuntime(DeviceCredentialState credential) {
        RuntimeDiscoveryResult result = runtimeDiscovery.discover(AgentType.CODEX);
        if (result.status() != RuntimeInstallStatus.INSTALLED) {
            log.warn("Codex runtime is not available locally: status={}, diagnostic={}",
                    result.status(), result.diagnostic());
            return;
        }
        controlPlaneClient.reportRuntime(credential, new RuntimeReportRequest(null, AgentType.CODEX,
                AgentRuntimeTypes.CODEX_APP_SERVER, result.version(), executablePath(result),
                capabilities(AgentType.CODEX)));
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

    private String stableLocalProjectId(Path realWorkspace) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256_ALGORITHM);
            byte[] hash = digest.digest(realWorkspace.toString().getBytes(StandardCharsets.UTF_8));
            return LOCAL_PROJECT_ID_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    private String defaultProjectName(Path realWorkspace) {
        Path fileName = realWorkspace.getFileName();
        return fileName == null ? realWorkspace.toString() : fileName.toString();
    }

    private String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
