package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.session.SessionStartRequest;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import com.wangbin.ai.agent.daemon.adapter.codex.protocol.CodexProtocolConstants;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.event.DeltaEventAggregator;
import com.wangbin.ai.agent.daemon.exception.AgentConnectionException;
import com.wangbin.ai.agent.daemon.process.ManagedProcess;
import com.wangbin.ai.agent.daemon.process.ProcessState;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
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
    void startsFreshRuntimeAfterClose(@TempDir Path workspace) {
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
        assertThat(adapter.runtimeState()).isEqualTo(CodexRuntimeState.READY);
    }

    @Test
    void emitsSessionStartedOnlyOnceWhenThreadStartedNotificationFollowsStartResponse(@TempDir Path workspace)
            throws Exception {
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

    private CodexAppServerAdapter newAdapter(Path workspace, List<TestRpcClient> clients) {
        AgentCodexProperties codexProperties = new AgentCodexProperties();
        codexProperties.setRequestTimeout(Duration.ofSeconds(1));
        AgentDaemonProperties daemonProperties = new AgentDaemonProperties();
        List<TestRpcClient> remainingClients = new ArrayList<>(clients);
        return new CodexAppServerAdapter(objectMapper, codexProperties, new TestAppServerProcess(), workspaceManager(workspace),
                new CodexEventMapper(), new DeltaEventAggregator(daemonProperties, scheduler),
                processIoExecutor) {

            @Override
            protected CodexJsonRpcClient createRpcClient(ManagedProcess process) {
                return remainingClients.removeFirst();
            }
        };
    }

    private WorkspaceManager workspaceManager(Path workspace) {
        return new WorkspaceManager() {

            @Override
            public Path validateWorkspace(String workspacePath) {
                return workspace;
            }

            @Override
            public Path resolveWithinWorkspace(Path workspace, String relativePath) {
                return workspace.resolve(relativePath);
            }

        };
    }

    private SessionStartRequest startRequest(Path workspace) {
        return new SessionStartRequest(1L, 11L, "device-1", "project-1", workspace.toString(),
                AgentType.CODEX, Map.of());
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

    private static final class TestRpcClient extends CodexJsonRpcClient {

        private final ObjectMapper objectMapper;
        private final String nativeSessionId;
        private final List<String> requests = new ArrayList<>();
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
            if (CodexProtocolConstants.METHOD_THREAD_START.equals(method)) {
                return CompletableFuture.completedFuture(objectMapper.createObjectNode()
                        .set("thread", objectMapper.createObjectNode().put("id", nativeSessionId)));
            }
            return CompletableFuture.completedFuture(objectMapper.createObjectNode());
        }

        @Override
        public void notify(String method, Object params) {
            // The adapter must send initialized, but this test only verifies lifecycle boundaries.
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
