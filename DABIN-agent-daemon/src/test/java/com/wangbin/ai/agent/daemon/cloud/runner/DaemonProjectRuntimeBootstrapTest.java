package com.wangbin.ai.agent.daemon.cloud.runner;

import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.runtime.AgentRuntimeTypes;
import com.wangbin.ai.agent.contract.session.AgentCapabilities;
import com.wangbin.ai.agent.contract.session.AgentSession;
import com.wangbin.ai.agent.contract.session.PromptCommand;
import com.wangbin.ai.agent.contract.session.SessionStartRequest;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.daemon.adapter.CodingAgentAdapter;
import com.wangbin.ai.agent.daemon.cloud.controlplane.ControlPlaneClient;
import com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceRequest;
import com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceResponse;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RegisterProjectRequest;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RegisterProjectResponse;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RelayTicketResponse;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RuntimeReportRequest;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.project.InMemoryLocalProjectRegistry;
import com.wangbin.ai.agent.daemon.project.LocalProject;
import com.wangbin.ai.agent.daemon.runtime.RuntimeDiscovery;
import com.wangbin.ai.agent.daemon.runtime.RuntimeDiscoveryResult;
import com.wangbin.ai.agent.daemon.runtime.RuntimeInstallStatus;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonProjectRuntimeBootstrapTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_LOCAL_PROJECT_ID = "local-prj-1";
    private static final String TEST_PLATFORM_PROJECT_ID = "prj-cloud-1";
    private static final String TEST_PROJECT_NAME = "Local Project";
    private static final String TEST_EXECUTABLE = "codex";
    private static final String TEST_VERSION = "1.2.3";

    @Test
    void bootstrapRegistersConfiguredProjectWithLocalRealPathAndReportsRuntime() throws IOException {
        Path workspace = testWorkspace();
        AgentDaemonProperties properties = new AgentDaemonProperties();
        AgentDaemonProperties.Project project = new AgentDaemonProperties.Project();
        project.setLocalProjectId(TEST_LOCAL_PROJECT_ID);
        project.setProjectName(TEST_PROJECT_NAME);
        project.setWorkspacePath(workspace.toString());
        project.setAgentType(AgentType.CODEX);
        properties.getProjects().add(project);
        FakeWorkspaceManager workspaceManager = new FakeWorkspaceManager(workspace.toAbsolutePath().normalize());
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        InMemoryLocalProjectRegistry registry = new InMemoryLocalProjectRegistry(workspaceManager);
        FakeRuntimeDiscovery runtimeDiscovery = new FakeRuntimeDiscovery(workspace.resolve(TEST_EXECUTABLE));

        new DaemonProjectRuntimeBootstrap(properties, workspaceManager, controlPlaneClient, registry,
                runtimeDiscovery, List.of(new FakeCodexAdapter())).bootstrap(credential());

        RegisterProjectRequest request = controlPlaneClient.projectRequest;
        assertThat(request.localProjectId()).isEqualTo(TEST_LOCAL_PROJECT_ID);
        assertThat(request.projectName()).isEqualTo(TEST_PROJECT_NAME);
        assertThat(request.workspacePath()).isEqualTo(workspace.toString());
        assertThat(request.workspaceRealPath()).isEqualTo(workspaceManager.realPath.toString());
        LocalProject localProject = registry.findByPlatformProjectId(TEST_PLATFORM_PROJECT_ID).orElseThrow();
        assertThat(localProject.realWorkspace()).isEqualTo(workspaceManager.realPath);
        RuntimeReportRequest runtimeRequest = controlPlaneClient.runtimeRequest;
        assertThat(runtimeRequest.agentType()).isEqualTo(AgentType.CODEX);
        assertThat(runtimeRequest.runtimeType()).isEqualTo(AgentRuntimeTypes.CODEX_APP_SERVER);
        assertThat(runtimeRequest.runtimeVersion()).isEqualTo(TEST_VERSION);
        assertThat(runtimeRequest.executablePath()).isEqualTo(runtimeDiscovery.resolvedPath.toString());
        assertThat(runtimeRequest.capabilities().prompt()).isTrue();
        assertThat(runtimeRequest.capabilities().permission()).isTrue();
    }

    private Path testWorkspace() throws IOException {
        Path baseDir = Path.of("target", "daemon-bootstrap-test").toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        return Files.createTempDirectory(baseDir, "workspace-");
    }

    private DeviceCredentialState credential() {
        DeviceCredentialState credential = new DeviceCredentialState();
        credential.setTenantId(TEST_TENANT_ID);
        credential.setDeviceId(TEST_DEVICE_ID);
        credential.setCredentialId("cred-1");
        credential.setCredentialSecret("secret");
        return credential;
    }

    private static final class FakeWorkspaceManager implements WorkspaceManager {

        private final Path realPath;

        private FakeWorkspaceManager(Path realPath) {
            this.realPath = realPath;
        }

        @Override
        public Path validateWorkspace(String workspacePath) {
            return realPath;
        }

        @Override
        public Path resolveWithinWorkspace(Path workspace, String relativePath) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeControlPlaneClient implements ControlPlaneClient {

        private RegisterProjectRequest projectRequest;
        private RuntimeReportRequest runtimeRequest;

        @Override
        public PairDeviceResponse pair(String controlPlaneUrl, PairDeviceRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RelayTicketResponse createDeviceRelayTicket(DeviceCredentialState credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RegisterProjectResponse registerProject(DeviceCredentialState credential,
                                                       RegisterProjectRequest request) {
            this.projectRequest = request;
            return new RegisterProjectResponse(10L, TEST_PLATFORM_PROJECT_ID, request.localProjectId(),
                    request.projectName(), "cloud must not override local workspace",
                    "cloud must not override local real path", request.agentType().name());
        }

        @Override
        public void reportRuntime(DeviceCredentialState credential, RuntimeReportRequest request) {
            this.runtimeRequest = request;
        }
    }

    private static final class FakeRuntimeDiscovery implements RuntimeDiscovery {

        private final Path resolvedPath;

        private FakeRuntimeDiscovery(Path resolvedPath) {
            this.resolvedPath = resolvedPath;
        }

        @Override
        public RuntimeDiscoveryResult discover(AgentType agentType) {
            return new RuntimeDiscoveryResult(agentType, RuntimeInstallStatus.INSTALLED, TEST_EXECUTABLE,
                    TEST_VERSION, resolvedPath, null, Map.of());
        }
    }

    private static final class FakeCodexAdapter implements CodingAgentAdapter {

        @Override
        public AgentType agentType() {
            return AgentType.CODEX;
        }

        @Override
        public AgentCapabilities capabilities() {
            return AgentCapabilities.codexDefault();
        }

        @Override
        public AgentSession startSession(SessionStartRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendPrompt(String sessionId, PromptCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void interrupt(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resolvePermission(String sessionId, String permissionId, PermissionDecision decision,
                                      String decisionCommandId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<AgentEvent> events(String sessionId) {
            return Flux.empty();
        }

        @Override
        public void closeSession(String sessionId) {
            throw new UnsupportedOperationException();
        }
    }
}
