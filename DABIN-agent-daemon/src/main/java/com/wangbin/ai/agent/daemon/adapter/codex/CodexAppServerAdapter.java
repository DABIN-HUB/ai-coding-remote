package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.agent.contract.session.AgentCapabilities;
import com.wangbin.ai.agent.contract.session.AgentSession;
import com.wangbin.ai.agent.contract.session.PromptCommand;
import com.wangbin.ai.agent.contract.session.SessionStartRequest;
import com.wangbin.ai.agent.daemon.adapter.CodingAgentAdapter;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessageKind;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import com.wangbin.ai.agent.daemon.event.DeltaEventAggregator;
import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.exception.AgentProtocolException;
import com.wangbin.ai.agent.daemon.exception.AgentSessionException;
import com.wangbin.ai.agent.daemon.process.ManagedProcess;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class CodexAppServerAdapter implements CodingAgentAdapter {

    private final ObjectMapper objectMapper;
    private final AgentCodexProperties codexProperties;
    private final CodexAppServerProcess appServerProcess;
    private final WorkspaceManager workspaceManager;
    private final CodexEventMapper eventMapper;
    private final DeltaEventAggregator eventAggregator;
    private final ExecutorService processIoExecutor;
    private final Sinks.Many<AgentEvent> eventSink = Sinks.many().replay().limit(256);
    private final Map<String, CodexSessionContext> platformSessions = new ConcurrentHashMap<>();
    private final Map<String, CodexSessionContext> nativeSessions = new ConcurrentHashMap<>();
    private final Map<String, String> activeTurnIds = new ConcurrentHashMap<>();

    private volatile ManagedProcess managedProcess;
    private volatile CodexJsonRpcClient rpcClient;
    private volatile boolean initialized;

    public CodexAppServerAdapter(ObjectMapper objectMapper,
                                 AgentCodexProperties codexProperties,
                                 CodexAppServerProcess appServerProcess,
                                 WorkspaceManager workspaceManager,
                                 CodexEventMapper eventMapper,
                                 DeltaEventAggregator eventAggregator,
                                 @Qualifier("agentProcessIoExecutor") ExecutorService processIoExecutor) {
        this.objectMapper = objectMapper;
        this.codexProperties = codexProperties;
        this.appServerProcess = appServerProcess;
        this.workspaceManager = workspaceManager;
        this.eventMapper = eventMapper;
        this.eventAggregator = eventAggregator;
        this.processIoExecutor = processIoExecutor;
    }

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
        Path workspace = workspaceManager.validateWorkspace(request.workspacePath());
        ensureStarted(workspace);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("cwd", workspace.toString());
        params.put("experimentalRawEvents", true);
        params.put("approvalPolicy", "on-request");
        JsonNode response = await(rpcClient.request("thread/start", params), "thread/start");
        String nativeSessionId = response.path("thread").path("id").asText(null);
        if (nativeSessionId == null || nativeSessionId.isBlank()) {
            throw new AgentProtocolException("thread/start response did not contain thread.id: " + response);
        }
        String platformSessionId = UUID.randomUUID().toString();
        CodexSessionContext context = new CodexSessionContext(platformSessionId, nativeSessionId, request.tenantId(),
                request.userId(), request.deviceId(), request.projectId(), workspace.toString(), AgentType.CODEX);
        platformSessions.put(platformSessionId, context);
        nativeSessions.put(nativeSessionId, context);
        emit(new AgentEvent(null, UUID.randomUUID().toString(), request.tenantId(), request.userId(),
                request.deviceId(), request.projectId(), platformSessionId, 0, AgentType.CODEX,
                com.wangbin.ai.agent.contract.enums.AgentEventType.SESSION_STARTED, null, null,
                new SessionPayload(nativeSessionId, AgentSessionStatus.RUNNING, null,
                        Map.of("source", "thread/start")),
                Map.of("nativeMethod", "thread/start")));
        return new AgentSession(platformSessionId, nativeSessionId, request.tenantId(), request.userId(),
                request.deviceId(), request.projectId(), AgentType.CODEX, AgentSessionStatus.RUNNING,
                capabilities(), Instant.now(), Map.of("workspacePath", workspace.toString()));
    }

    @Override
    public void sendPrompt(String sessionId, PromptCommand command) {
        CodexSessionContext context = requireSession(sessionId);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", context.nativeSessionId());
        params.put("cwd", context.workspacePath());
        params.put("clientUserMessageId", command.commandId());
        ArrayNode input = params.putArray("input");
        ObjectNode text = input.addObject();
        text.put("type", "text");
        text.put("text", command.prompt());
        JsonNode response = await(rpcClient.request("turn/start", params), "turn/start");
        String turnId = response.path("turn").path("id").asText(null);
        if (turnId != null) {
            activeTurnIds.put(context.platformSessionId(), turnId);
        }
    }

    @Override
    public void interrupt(String sessionId) {
        CodexSessionContext context = requireSession(sessionId);
        String turnId = activeTurnIds.get(sessionId);
        if (turnId == null) {
            throw new AgentSessionException("no active Codex turn for session " + sessionId);
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", context.nativeSessionId());
        params.put("turnId", turnId);
        await(rpcClient.request("turn/interrupt", params), "turn/interrupt");
    }

    @Override
    public void resolvePermission(String sessionId, String permissionId, PermissionDecision decision) {
        throw new AgentCapabilityException("Codex permission resolution is not wired in this first-stage adapter");
    }

    @Override
    public Flux<AgentEvent> events(String sessionId) {
        return eventSink.asFlux().filter(event -> sessionId.equals(event.sessionId()));
    }

    @Override
    public void closeSession(String sessionId) {
        CodexSessionContext removed = platformSessions.remove(sessionId);
        if (removed != null) {
            nativeSessions.remove(removed.nativeSessionId());
            activeTurnIds.remove(sessionId);
        }
        if (platformSessions.isEmpty() && managedProcess != null) {
            managedProcess.close();
        }
    }

    private synchronized void ensureStarted(Path workspace) {
        if (initialized && rpcClient != null) {
            return;
        }
        managedProcess = appServerProcess.start(workspace);
        rpcClient = new CodexJsonRpcClient(objectMapper, managedProcess.stdout(),
                new OutputStreamWriter(managedProcess.process().getOutputStream(), StandardCharsets.UTF_8),
                processIoExecutor, codexProperties.getRequestTimeout());
        rpcClient.messages().subscribe(this::handleMessage);
        rpcClient.protocolIssues().subscribe(issue -> {
            // Protocol issues are diagnostic. They are intentionally not emitted as AgentEvent without a session.
        });
        rpcClient.start();
        initialize();
    }

    private void initialize() {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "ai_coding_remote");
        clientInfo.put("title", "AI Coding Remote");
        clientInfo.put("version", "0.1.0");
        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.put("experimentalApi", codexProperties.isExperimentalApi());
        await(rpcClient.request("initialize", params), "initialize");
        rpcClient.notify("initialized", null);
        initialized = true;
    }

    private void handleMessage(CodexRpcMessage message) {
        CodexSessionContext context = resolveContext(message.params());
        if (context == null && message.kind() == CodexRpcMessageKind.SERVER_REQUEST) {
            context = platformSessions.values().stream().findFirst().orElse(null);
        }
        captureActiveTurn(context, message.params());
        for (AgentEvent event : eventMapper.map(message, context)) {
            emit(event);
        }
    }

    private void emit(AgentEvent event) {
        for (AgentEvent aggregated : eventAggregator.accept(event)) {
            eventSink.tryEmitNext(aggregated);
        }
    }

    private void captureActiveTurn(CodexSessionContext context, JsonNode params) {
        if (context == null || params == null) {
            return;
        }
        String turnId = params.path("turn").path("id").asText(null);
        if (turnId == null) {
            turnId = params.path("turnId").asText(null);
        }
        if (turnId != null) {
            activeTurnIds.put(context.platformSessionId(), turnId);
        }
    }

    private CodexSessionContext resolveContext(JsonNode params) {
        if (params == null || params.isNull()) {
            return null;
        }
        String threadId = params.path("threadId").asText(null);
        if (threadId == null) {
            threadId = params.path("thread").path("id").asText(null);
        }
        if (threadId == null) {
            threadId = params.path("conversationId").asText(null);
        }
        return threadId == null ? null : nativeSessions.get(threadId);
    }

    private CodexSessionContext requireSession(String sessionId) {
        CodexSessionContext context = platformSessions.get(sessionId);
        if (context == null) {
            throw new AgentSessionException("unknown Codex session: " + sessionId);
        }
        return context;
    }

    private JsonNode await(java.util.concurrent.CompletableFuture<JsonNode> future, String operation) {
        try {
            return future.get(codexProperties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            throw new AgentProtocolException("Codex operation failed: " + operation, ex);
        }
    }

}
