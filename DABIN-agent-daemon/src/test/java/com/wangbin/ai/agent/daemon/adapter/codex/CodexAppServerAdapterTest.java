package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.enums.PermissionResolutionStatus;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentEventExtensionKeys;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.agent.contract.event.PermissionRequiredPayload;
import com.wangbin.ai.agent.contract.event.PermissionResolvedPayload;
import com.wangbin.ai.agent.contract.permission.CommandExecutionPermissionDetail;
import com.wangbin.ai.agent.contract.session.PromptCommand;
import com.wangbin.ai.agent.contract.session.SessionStartRequest;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import com.wangbin.ai.agent.daemon.adapter.codex.protocol.CodexProtocolConstants;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.event.DeltaEventAggregator;
import com.wangbin.ai.agent.daemon.event.SerializedSessionEventEmitter;
import com.wangbin.ai.agent.daemon.event.change.AgentChangeSetAccumulator;
import com.wangbin.ai.agent.daemon.event.change.DaemonChangeSetIdFactory;
import com.wangbin.ai.agent.daemon.event.change.SensitivePathPolicy;
import com.wangbin.ai.agent.daemon.event.change.UnifiedDiffParser;
import com.wangbin.ai.agent.daemon.event.change.WorkspaceRelativePathNormalizer;
import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.exception.AgentConnectionException;
import com.wangbin.ai.agent.daemon.exception.AgentProtocolException;
import com.wangbin.ai.agent.daemon.exception.AgentSessionException;
import com.wangbin.ai.agent.daemon.process.ManagedProcess;
import com.wangbin.ai.agent.daemon.process.ProcessState;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodexAppServerAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService processIoExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        processIoExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    @Test
    void rejectsUnroutablePermissionServerRequestWithoutGuessingSession() throws Exception {
        TestRpcClient rpcClient = new TestRpcClient(objectMapper, processIoExecutor, "native-unused");
        CodexAppServerAdapter adapter = newAdapter(Path.of("."), List.of(rpcClient));
        ReflectionTestUtils.setField(adapter, "rpcClient", rpcClient);

        adapter.handleMessage(CodexRpcMessage.serverRequest("approval-1",
                CodexProtocolConstants.METHOD_PERMISSION_REQUEST_APPROVAL,
                objectMapper.readTree("{\"threadId\":\"missing-native-session\"}")));

        assertThat(rpcClient.errorCodes).containsExactly(CodexProtocolConstants.JSON_RPC_ROUTE_UNAVAILABLE);
        assertThat(rpcClient.protocolWarningCodes).containsExactly("unroutable_server_request");
    }

    @Test
    void rejectsUnknownServerRequestMethodWithoutTreatingItAsPermission() throws Exception {
        TestRpcClient rpcClient = new TestRpcClient(objectMapper, processIoExecutor, "native-unused");
        CodexAppServerAdapter adapter = newAdapter(Path.of("."), List.of(rpcClient));
        ReflectionTestUtils.setField(adapter, "rpcClient", rpcClient);

        adapter.handleMessage(CodexRpcMessage.serverRequest("request-1", "unknown/serverRequest",
                objectMapper.readTree("{\"threadId\":\"missing-native-session\"}")));

        assertThat(rpcClient.errorCodes).containsExactly(CodexProtocolConstants.JSON_RPC_METHOD_NOT_FOUND);
        assertThat(rpcClient.protocolWarningCodes).containsExactly("unknown_server_request");
    }

    @Test
    void startsFreshRuntimeAfterClose() throws Exception {
        Path workspace = testWorkspace("startsFreshRuntimeAfterClose");
        TestRpcClient firstClient = new TestRpcClient(objectMapper, processIoExecutor, "native-1");
        TestRpcClient secondClient = new TestRpcClient(objectMapper, processIoExecutor, "native-2");
        CodexAppServerAdapter adapter = newAdapter(workspace, List.of(firstClient, secondClient));

        var firstSession = adapter.startSession(startRequest(workspace));
        adapter.closeSession(firstSession.platformSessionId());
        var secondSession = adapter.startSession(startRequest(workspace));

        assertThat(firstSession.nativeSessionId()).isEqualTo("native-1");
        assertThat(secondSession.nativeSessionId()).isEqualTo("native-2");
        assertThat(firstClient.closed).isTrue();
        assertThat(secondClient.closed).isFalse();
        assertThat(firstClient.requests).containsExactly(CodexProtocolConstants.METHOD_INITIALIZE,
                CodexProtocolConstants.METHOD_THREAD_START);
        assertThat(secondClient.requests).containsExactly(CodexProtocolConstants.METHOD_INITIALIZE,
                CodexProtocolConstants.METHOD_THREAD_START);
        assertThat(firstClient.requestParams.get(1).has("experimentalRawEvents")).isFalse();
        assertThat(adapter.runtimeState()).isEqualTo(CodexRuntimeState.READY);
    }

    @Test
    void startSessionFailureIncludesCodexErrorSummary() throws Exception {
        Path workspace = testWorkspace("startSessionFailureIncludesCodexErrorSummary");
        TestRpcClient rpcClient = new FailingInitializeRpcClient(objectMapper, processIoExecutor);
        CodexAppServerAdapter adapter = newAdapter(workspace, List.of(rpcClient));

        assertThatThrownBy(() -> adapter.startSession(startRequest(workspace)))
                .isInstanceOf(AgentProtocolException.class)
                .hasMessageContaining("Codex operation failed: " + CodexProtocolConstants.METHOD_INITIALIZE)
                .hasMessageContaining("codex init rejected");
    }

    @Test
    void emitsSessionStartedOnlyOnceWhenThreadStartedNotificationFollowsStartResponse()
            throws Exception {
        Path workspace = testWorkspace("emitsSessionStartedOnlyOnce");
        TestRpcClient rpcClient = new TestRpcClient(objectMapper, processIoExecutor, "native-1");
        CodexAppServerAdapter adapter = newAdapter(workspace, List.of(rpcClient));

        var session = adapter.startSession(startRequest(workspace));
        List<AgentEvent> events = new ArrayList<>();
        adapter.events(session.platformSessionId()).subscribe(events::add);

        adapter.handleMessage(CodexRpcMessage.notification(CodexProtocolConstants.METHOD_THREAD_STARTED,
                objectMapper.readTree("{\"threadId\":\"native-1\"}")));
        adapter.handleMessage(CodexRpcMessage.notification(CodexProtocolConstants.METHOD_THREAD_STARTED,
                objectMapper.readTree("{\"threadId\":\"native-1\"}")));

        assertThat(events)
                .filteredOn(event -> event.type() == AgentEventType.SESSION_STARTED)
                .hasSize(1)
                .extracting(AgentEvent::seq)
                .containsExactly(1L);
    }

    @Test
    void promptCommandIdCorrelatesTurnEventsAndClearsAfterSessionIdle() throws Exception {
        Path workspace = testWorkspace("promptCommandIdCorrelatesTurnEvents");
        TestRpcClient rpcClient = new TestRpcClient(objectMapper, processIoExecutor, "native-1");
        CodexAppServerAdapter adapter = newAdapter(workspace, List.of(rpcClient));
        var session = adapter.startSession(startRequest(workspace));
        List<AgentEvent> events = new ArrayList<>();
        adapter.events(session.platformSessionId()).subscribe(events::add);

        adapter.sendPrompt(session.platformSessionId(), new PromptCommand("cmd-123", "hello", Map.of()));
        adapter.handleMessage(CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ITEM_COMPLETED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "item": {
                            "id": "native-msg-1",
                            "type": "agentMessage",
                            "phase": "final_answer",
                            "text": "answer"
                          }
                        }
                        """)));
        adapter.handleMessage(CodexRpcMessage.notification(CodexProtocolConstants.METHOD_TURN_COMPLETED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turn": {
                            "id": "turn-1",
                            "status": "completed",
                            "items": []
                          }
                        }
                        """)));

        AgentEvent messageEvent = events.stream()
                .filter(event -> event.type() == AgentEventType.AGENT_MESSAGE)
                .findFirst()
                .orElseThrow();
        assertThat(messageEvent.extensions())
                .containsEntry(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, "cmd-123");
        AgentMessagePayload payload = (AgentMessagePayload) messageEvent.payload();
        assertThat(payload.messageId()).isEqualTo("native-msg-1");
        assertThat(payload.extensions())
                .containsEntry(AgentEventExtensionKeys.NATIVE_ITEM_ID, "native-msg-1");
        assertThat(events.stream()
                .filter(event -> event.type() == AgentEventType.SESSION_IDLE)
                .findFirst()
                .orElseThrow()
                .extensions()).containsEntry(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, "cmd-123");

        adapter.sendPrompt(session.platformSessionId(), new PromptCommand("cmd-456", "next", Map.of()));

        assertThat(rpcClient.requests)
                .contains(CodexProtocolConstants.METHOD_TURN_START, CodexProtocolConstants.METHOD_TURN_START);
        assertThat(rpcClient.requestParams.stream()
                .filter(params -> "cmd-123".equals(params.path("clientUserMessageId").asText(null))))
                .hasSize(1);
        assertThat(rpcClient.requestParams.stream()
                .filter(params -> "cmd-456".equals(params.path("clientUserMessageId").asText(null))))
                .hasSize(1);
    }

    @Test
    void retryableErrorKeepsActiveCommandAndTerminalErrorClearsIt() throws Exception {
        Path workspace = testWorkspace("retryableErrorKeepsActiveCommand");
        TestRpcClient rpcClient = new TestRpcClient(objectMapper, processIoExecutor, "native-1");
        CodexAppServerAdapter adapter = newAdapter(workspace, List.of(rpcClient));
        var session = adapter.startSession(startRequest(workspace));

        adapter.sendPrompt(session.platformSessionId(), new PromptCommand("cmd-123", "hello", Map.of()));
        adapter.handleMessage(CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ERROR,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "code": "responseStreamDisconnected",
                          "message": "temporary disconnect",
                          "willRetry": true
                        }
                        """)));

        assertThatThrownBy(() -> adapter.sendPrompt(session.platformSessionId(),
                new PromptCommand("cmd-456", "busy", Map.of())))
                .isInstanceOf(AgentSessionException.class);

        adapter.handleMessage(CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ERROR,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "code": "codex_error",
                          "message": "terminal failure",
                          "willRetry": false
                        }
                        """)));
        adapter.sendPrompt(session.platformSessionId(), new PromptCommand("cmd-789", "next", Map.of()));

        assertThat(rpcClient.requestParams.stream()
                .filter(params -> "cmd-123".equals(params.path("clientUserMessageId").asText(null))))
                .hasSize(1);
        assertThat(rpcClient.requestParams.stream()
                .filter(params -> "cmd-789".equals(params.path("clientUserMessageId").asText(null))))
                .hasSize(1);
    }

    @Test
    void commandApprovalUsesPlatformPermissionIdAndPreservesNumericJsonRpcId() throws Exception {
        Path workspace = testWorkspace("commandApprovalPreservesNumericId");
        TestRpcClient rpcClient = new TestRpcClient(objectMapper, processIoExecutor, "native-1");
        CodexAppServerAdapter adapter = newAdapter(workspace, List.of(rpcClient));
        var session = adapter.startSession(startRequest(workspace));
        List<AgentEvent> events = new ArrayList<>();
        adapter.events(session.platformSessionId()).subscribe(events::add);
        adapter.sendPrompt(session.platformSessionId(), new PromptCommand("cmd-123", "hello", Map.of()));

        adapter.handleMessage(CodexRpcMessage.serverRequest(objectMapper.readTree("123"),
                CodexProtocolConstants.METHOD_COMMAND_REQUEST_APPROVAL,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "itemId": "item-1",
                          "command": "git status",
                          "cwd": ".",
                          "reason": "inspect",
                          "availableDecisions": ["accept", "decline"]
                        }
                        """)));

        AgentEvent requiredEvent = events.stream()
                .filter(event -> event.type() == AgentEventType.PERMISSION_REQUIRED)
                .findFirst()
                .orElseThrow();
        assertThat(requiredEvent.extensions())
                .containsEntry(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, "cmd-123");
        PermissionRequiredPayload required = (PermissionRequiredPayload) requiredEvent.payload();
        assertThat(required.permissionId()).startsWith("perm_").isNotEqualTo("123");
        assertThat(required.detail()).isInstanceOf(CommandExecutionPermissionDetail.class);
        assertThat(((CommandExecutionPermissionDetail) required.detail()).command()).isEqualTo("git status");

        adapter.resolvePermission(session.platformSessionId(), required.permissionId(), PermissionDecision.APPROVED,
                "cmd-decision-1");
        adapter.handleMessage(CodexRpcMessage.notification(CodexProtocolConstants.METHOD_SERVER_REQUEST_RESOLVED,
                objectMapper.readTree("{\"threadId\":\"native-1\",\"requestId\":123}")));

        assertThat(rpcClient.responseIds).hasSize(1);
        assertThat(rpcClient.responseIds.getFirst().isNumber()).isTrue();
        assertThat(rpcClient.responseIds.getFirst().asInt()).isEqualTo(123);
        assertThat(rpcClient.responseResults.getFirst().path("decision").asText()).isEqualTo("accept");
        PermissionResolvedPayload resolved = (PermissionResolvedPayload) events.stream()
                .filter(event -> event.type() == AgentEventType.PERMISSION_RESOLVED)
                .findFirst()
                .orElseThrow()
                .payload();
        assertThat(resolved.permissionId()).isEqualTo(required.permissionId());
        assertThat(resolved.decisionCommandId()).isEqualTo("cmd-decision-1");
        assertThat(resolved.resolutionStatus()).isEqualTo(PermissionResolutionStatus.APPROVED);
    }

    @Test
    void fileApprovalRejectsWorkspaceEscapeAndStringJsonRpcIdIsPreserved() throws Exception {
        Path workspace = testWorkspace("fileApprovalStringId");
        TestRpcClient rpcClient = new TestRpcClient(objectMapper, processIoExecutor, "native-1");
        CodexAppServerAdapter adapter = newAdapter(workspace, List.of(rpcClient));
        var session = adapter.startSession(startRequest(workspace));
        List<AgentEvent> events = new ArrayList<>();
        adapter.events(session.platformSessionId()).subscribe(events::add);
        adapter.sendPrompt(session.platformSessionId(), new PromptCommand("cmd-123", "hello", Map.of()));

        adapter.handleMessage(CodexRpcMessage.serverRequest(objectMapper.readTree("\"abc\""),
                CodexProtocolConstants.METHOD_FILE_CHANGE_REQUEST_APPROVAL,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "itemId": "file-1",
                          "reason": "update file",
                          "changes": [{"path": "README.md", "kind": {"type": "update"}}],
                          "availableDecisions": ["acceptForSession", "cancel"]
                        }
                        """)));
        PermissionRequiredPayload required = (PermissionRequiredPayload) events.stream()
                .filter(event -> event.type() == AgentEventType.PERMISSION_REQUIRED)
                .findFirst()
                .orElseThrow()
                .payload();

        adapter.resolvePermission(session.platformSessionId(), required.permissionId(),
                PermissionDecision.APPROVED_FOR_SESSION, "cmd-decision-2");

        assertThat(rpcClient.responseIds.getFirst().isTextual()).isTrue();
        assertThat(rpcClient.responseIds.getFirst().asText()).isEqualTo("abc");
        assertThat(rpcClient.responseResults.getFirst().path("decision").asText()).isEqualTo("acceptForSession");

        long requiredCount = events.stream().filter(event -> event.type() == AgentEventType.PERMISSION_REQUIRED)
                .count();
        adapter.handleMessage(CodexRpcMessage.serverRequest(objectMapper.readTree("\"escape\""),
                CodexProtocolConstants.METHOD_FILE_CHANGE_REQUEST_APPROVAL,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "itemId": "file-escape",
                          "changes": [{"path": "../outside.txt", "kind": {"type": "update"}}],
                          "availableDecisions": ["accept", "decline"]
                        }
                        """)));

        assertThat(events.stream().filter(event -> event.type() == AgentEventType.PERMISSION_REQUIRED).count())
                .isEqualTo(requiredCount);
        assertThat(rpcClient.responseResults.getLast().path("decision").asText()).isEqualTo("decline");
    }

    private CodexAppServerAdapter newAdapter(Path workspace, List<TestRpcClient> clients) {
        AgentCodexProperties codexProperties = new AgentCodexProperties();
        codexProperties.setRequestTimeout(Duration.ofSeconds(1));
        AgentDaemonProperties daemonProperties = new AgentDaemonProperties();
        List<TestRpcClient> remainingClients = new ArrayList<>(clients);
        CodexPermissionDecisionMapper decisionMapper = new CodexPermissionDecisionMapper(objectMapper);
        return new CodexAppServerAdapter(objectMapper, codexProperties, new TestAppServerProcess(), workspaceManager(workspace),
                new CodexEventMapper(), new CodexPermissionRequestMapper(codexProperties, decisionMapper),
                decisionMapper, new CodexPendingPermissionRegistry(codexProperties),
                changeSetAccumulator(workspaceManager(workspace), codexProperties),
                new DeltaEventAggregator(daemonProperties, scheduler),
                new SerializedSessionEventEmitter(),
                processIoExecutor) {

            @Override
            protected CodexJsonRpcClient createRpcClient(ManagedProcess process) {
                return remainingClients.removeFirst();
            }
        };
    }

    private AgentChangeSetAccumulator changeSetAccumulator(WorkspaceManager workspaceManager,
                                                           AgentCodexProperties codexProperties) {
        WorkspaceRelativePathNormalizer normalizer = new WorkspaceRelativePathNormalizer(workspaceManager);
        SensitivePathPolicy sensitivePathPolicy = new SensitivePathPolicy();
        return new AgentChangeSetAccumulator(normalizer,
                new UnifiedDiffParser(normalizer, sensitivePathPolicy, codexProperties),
                sensitivePathPolicy, new DaemonChangeSetIdFactory(), codexProperties);
    }

    private WorkspaceManager workspaceManager(Path workspace) {
        return new WorkspaceManager() {

            @Override
            public Path validateWorkspace(String workspacePath) {
                return workspace;
            }

            @Override
            public Path resolveWithinWorkspace(Path workspace, String relativePath) {
                Path resolved = workspace.resolve(relativePath).normalize();
                if (!resolved.startsWith(workspace)) {
                    throw new AgentCapabilityException("path escapes workspace");
                }
                return resolved;
            }

        };
    }

    private SessionStartRequest startRequest(Path workspace) {
        return new SessionStartRequest(null, 1L, 11L, "device-1", "project-1", workspace.toString(),
                AgentType.CODEX, Map.of());
    }

    private Path testWorkspace(String name) throws Exception {
        Path workspace = Path.of("target", "codex-adapter-test", name).toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        return workspace;
    }

    private static final class TestAppServerProcess extends CodexAppServerProcess {

        private TestAppServerProcess() {
            super(new AgentCodexProperties(),
                    (spec, stderrConsumer) -> new ManagedProcess(new TestProcess(),
                            new AtomicReference<>(ProcessState.RUNNING)),
                    executable -> null);
        }

        @Override
        public ManagedProcess start(Path cwd) {
            return new ManagedProcess(new TestProcess(), new AtomicReference<>(ProcessState.RUNNING));
        }

    }

    private static class TestRpcClient extends CodexJsonRpcClient {

        private final ObjectMapper objectMapper;
        private final String nativeSessionId;
        private final List<String> requests = new ArrayList<>();
        private final List<JsonNode> requestParams = new ArrayList<>();
        private final List<JsonNode> responseIds = new ArrayList<>();
        private final List<JsonNode> responseResults = new ArrayList<>();
        private final List<Integer> errorCodes = new ArrayList<>();
        private final List<String> protocolWarningCodes = new ArrayList<>();
        private boolean closed;

        private TestRpcClient(ObjectMapper objectMapper, ExecutorService executor, String nativeSessionId) {
            super(objectMapper, null, new StringWriter(), executor, Duration.ofSeconds(1));
            this.objectMapper = objectMapper;
            this.nativeSessionId = nativeSessionId;
        }

        @Override
        public CompletableFuture<JsonNode> request(String method, Object params) {
            requests.add(method);
            requestParams.add(objectMapper.valueToTree(params));
            if (CodexProtocolConstants.METHOD_THREAD_START.equals(method)) {
                return CompletableFuture.completedFuture(objectMapper.createObjectNode()
                        .set("thread", objectMapper.createObjectNode().put("id", nativeSessionId)));
            }
            if (CodexProtocolConstants.METHOD_TURN_START.equals(method)) {
                return CompletableFuture.completedFuture(objectMapper.createObjectNode()
                        .set("turn", objectMapper.createObjectNode().put("id", "turn-" + requests.size())));
            }
            return CompletableFuture.completedFuture(objectMapper.createObjectNode());
        }

        @Override
        public void notify(String method, Object params) {
            // The adapter must send initialized, but this test only verifies lifecycle boundaries.
        }

        @Override
        public void respond(JsonNode id, Object result) {
            responseIds.add(id == null ? null : id.deepCopy());
            responseResults.add(objectMapper.valueToTree(result));
        }

        @Override
        public void respondError(JsonNode id, int code, String message) {
            errorCodes.add(code);
        }

        @Override
        public void protocolWarning(String code, String message, String rawLine) {
            protocolWarningCodes.add(code);
        }

        @Override
        public void closeWithError(Throwable throwable) {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

    }

    private static final class FailingInitializeRpcClient extends TestRpcClient {

        private FailingInitializeRpcClient(ObjectMapper objectMapper, ExecutorService executor) {
            super(objectMapper, executor, "native-unused");
        }

        @Override
        public CompletableFuture<JsonNode> request(String method, Object params) {
            if (CodexProtocolConstants.METHOD_INITIALIZE.equals(method)) {
                CompletableFuture<JsonNode> future = new CompletableFuture<>();
                future.completeExceptionally(new AgentProtocolException("codex init rejected"));
                return future;
            }
            return super.request(method, params);
        }
    }

    private static final class TestProcess extends Process {

        private final InputStream inputStream = new ByteArrayInputStream(new byte[0]);
        private final InputStream errorStream = new ByteArrayInputStream(new byte[0]);
        private final OutputStream outputStream = new ByteArrayOutputStream();
        private boolean alive = true;

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public InputStream getErrorStream() {
            return errorStream;
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            alive = false;
            return true;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public long pid() {
            return 1L;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

    }

}
