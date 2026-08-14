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
import com.wangbin.ai.agent.daemon.project.AuthorizedProjectState;
import com.wangbin.ai.agent.daemon.project.AuthorizedProjectStore;
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

        new DaemonProjectRuntimeBootstrap(properties, new FakeAuthorizedProjectStore(List.of()), workspaceManager,
                controlPlaneClient, registry,
                runtimeDiscovery, List.of(new FakeCodexAdapter())).bootstrap(credential());

        RegisterProjectRequest request = controlPlaneClient.projectRequests.getFirst();
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

    @Test
    void bootstrapRegistersMultipleAuthorizedProjectsAndSkipsInvalidOne() throws IOException {
        Path workspaceA = testWorkspace();
        Path workspaceC = testWorkspace();
        Path invalidWorkspace = workspaceA.resolve("missing");
        AgentDaemonProperties properties = new AgentDaemonProperties();
        FakeWorkspaceManager workspaceManager = new FakeWorkspaceManager(workspaceA.toAbsolutePath().normalize());
        workspaceManager.addRealPath(workspaceC.toString(), workspaceC.toAbsolutePath().normalize());
        workspaceManager.rejectPath(invalidWorkspace.toString());
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        InMemoryLocalProjectRegistry registry = new InMemoryLocalProjectRegistry(workspaceManager);
        FakeRuntimeDiscovery runtimeDiscovery = new FakeRuntimeDiscovery(workspaceA.resolve(TEST_EXECUTABLE));

        new DaemonProjectRuntimeBootstrap(properties, new FakeAuthorizedProjectStore(List.of(
                new AuthorizedProjectState("local-a", "Project A", workspaceA.toString(), AgentType.CODEX),
                new AuthorizedProjectState("local-b", "Project B", invalidWorkspace.toString(), AgentType.CODEX),
                new AuthorizedProjectState("local-c", "Project C", workspaceC.toString(), AgentType.CODEX)
        )), workspaceManager, controlPlaneClient, registry,
                runtimeDiscovery, List.of(new FakeCodexAdapter())).bootstrap(credential());

        assertThat(controlPlaneClient.projectRequests)
                .extracting(RegisterProjectRequest::localProjectId)
                .containsExactly("local-a", "local-c");
    }

    @Test
    void runtimeReportFailureShouldNotAbortBootstrap() throws IOException {
        Path workspace = testWorkspace();
        AgentDaemonProperties properties = new AgentDaemonProperties();
        FakeWorkspaceManager workspaceManager = new FakeWorkspaceManager(workspace.toAbsolutePath().normalize());
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        controlPlaneClient.failRuntimeReport = true;
        InMemoryLocalProjectRegistry registry = new InMemoryLocalProjectRegistry(workspaceManager);
        FakeRuntimeDiscovery runtimeDiscovery = new FakeRuntimeDiscovery(workspace.resolve(TEST_EXECUTABLE));

        new DaemonProjectRuntimeBootstrap(properties, new FakeAuthorizedProjectStore(List.of(
                new AuthorizedProjectState("local-a", "Project A", workspace.toString(), AgentType.CODEX)
        )), workspaceManager, controlPlaneClient, registry,
                runtimeDiscovery, List.of(new FakeCodexAdapter())).bootstrap(credential());

        assertThat(controlPlaneClient.projectRequests)
                .extracting(RegisterProjectRequest::localProjectId)
                .containsExactly("local-a");
        assertThat(registry.findByPlatformProjectId(TEST_PLATFORM_PROJECT_ID)).isPresent();
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
        private final Map<String, Path> realPaths = new java.util.HashMap<>();
        private final java.util.Set<String> rejectedPaths = new java.util.HashSet<>();

        private FakeWorkspaceManager(Path realPath) {
            this.realPath = realPath;
        }

        private void addRealPath(String workspacePath, Path realPath) {
            realPaths.put(workspacePath, realPath);
        }

        private void rejectPath(String workspacePath) {
            rejectedPaths.add(workspacePath);
        }

        @Override
        public Path validateWorkspace(String workspacePath) {
            if (rejectedPaths.contains(workspacePath)) {
                throw new IllegalArgumentException("invalid workspace");
            }
            return realPaths.getOrDefault(workspacePath, realPath);
        }

        @Override
        public Path resolveWithinWorkspace(Path workspace, String relativePath) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeControlPlaneClient implements ControlPlaneClient {

        private RegisterProjectRequest projectRequest;
        private final List<RegisterProjectRequest> projectRequests = new java.util.ArrayList<>();
        private RuntimeReportRequest runtimeRequest;
        private boolean failRuntimeReport;

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
            this.projectRequests.add(request);
            return new RegisterProjectResponse(10L, TEST_PLATFORM_PROJECT_ID, request.localProjectId(),
                    request.projectName(), "cloud must not override local workspace",
                    "cloud must not override local real path", request.agentType().name());
        }

        @Override
        public void reportRuntime(DeviceCredentialState credential, RuntimeReportRequest request) {
            if (failRuntimeReport) {
                throw new IllegalStateException("runtime report unavailable");
            }
            this.runtimeRequest = request;
        }
    }

    private static class FakeAuthorizedProjectStore extends AuthorizedProjectStore {

        private List<AuthorizedProjectState> projects;

        FakeAuthorizedProjectStore(List<AuthorizedProjectState> projects) {
            super(new com.fasterxml.jackson.databind.ObjectMapper());
            this.projects = projects;
        }

        @Override
        public synchronized List<AuthorizedProjectState> load() {
            return projects;
        }

        @Override
        public synchronized List<AuthorizedProjectState> addProjects(java.util.Collection<AuthorizedProjectState> projects) {
            this.projects = List.copyOf(projects);
            return this.projects;
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
