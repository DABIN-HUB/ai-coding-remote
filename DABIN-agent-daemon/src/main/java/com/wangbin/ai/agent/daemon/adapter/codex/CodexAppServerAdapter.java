package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.enums.SessionControlAction;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentEventPayload;
import com.wangbin.ai.agent.contract.event.AgentEventExtensionKeys;
import com.wangbin.ai.agent.contract.event.PermissionRequiredPayload;
import com.wangbin.ai.agent.contract.event.PermissionResolvedPayload;
import com.wangbin.ai.agent.contract.event.SessionControlTimeoutPayload;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.agent.contract.event.WarningPayload;
import com.wangbin.ai.agent.contract.session.AgentCapabilities;
import com.wangbin.ai.agent.contract.session.AgentSession;
import com.wangbin.ai.agent.contract.session.PromptCommand;
import com.wangbin.ai.agent.contract.session.SessionStartRequest;
import com.wangbin.ai.agent.daemon.adapter.CodingAgentAdapter;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessageKind;
import com.wangbin.ai.agent.daemon.adapter.codex.protocol.CodexProtocolConstants;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import com.wangbin.ai.agent.daemon.event.DeltaEventAggregator;
import com.wangbin.ai.agent.daemon.event.AgentCommandLifecyclePolicy;
import com.wangbin.ai.agent.daemon.event.SerializedSessionEventEmitter;
import com.wangbin.ai.agent.daemon.event.change.AgentChangeSetAccumulator;
import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.exception.AgentConnectionException;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Component
public class CodexAppServerAdapter implements CodingAgentAdapter {

    private static final int ERROR_SUMMARY_MAX_LENGTH = 256;

    private final ObjectMapper objectMapper;
    private final AgentCodexProperties codexProperties;
    private final CodexAppServerProcess appServerProcess;
    private final WorkspaceManager workspaceManager;
    private final CodexEventMapper eventMapper;
    private final CodexPermissionRequestMapper permissionRequestMapper;
    private final CodexPermissionDecisionMapper permissionDecisionMapper;
    private final CodexPendingPermissionRegistry pendingPermissionRegistry;
    private final AgentChangeSetAccumulator changeSetAccumulator;
    private final DeltaEventAggregator eventAggregator;
    private final SerializedSessionEventEmitter sessionEventEmitter;
    private final ExecutorService processIoExecutor;
    private final Sinks.Many<AgentEvent> eventSink = Sinks.many().replay().limit(256);
    private final Map<String, CodexSessionContext> platformSessions = new ConcurrentHashMap<>();
    private final Map<String, CodexSessionContext> nativeSessions = new ConcurrentHashMap<>();
    private final Map<String, String> activeTurnIds = new ConcurrentHashMap<>();

    private volatile ManagedProcess managedProcess;
    private volatile CodexJsonRpcClient rpcClient;
    private volatile CodexRuntimeState runtimeState = CodexRuntimeState.STOPPED;

    public CodexAppServerAdapter(ObjectMapper objectMapper,
                                 AgentCodexProperties codexProperties,
                                 CodexAppServerProcess appServerProcess,
                                 WorkspaceManager workspaceManager,
                                 CodexEventMapper eventMapper,
                                 CodexPermissionRequestMapper permissionRequestMapper,
                                 CodexPermissionDecisionMapper permissionDecisionMapper,
                                 CodexPendingPermissionRegistry pendingPermissionRegistry,
                                 AgentChangeSetAccumulator changeSetAccumulator,
                                 DeltaEventAggregator eventAggregator,
                                 SerializedSessionEventEmitter sessionEventEmitter,
                                 @Qualifier("agentProcessIoExecutor") ExecutorService processIoExecutor) {
        this.objectMapper = objectMapper;
        this.codexProperties = codexProperties;
        this.appServerProcess = appServerProcess;
        this.workspaceManager = workspaceManager;
        this.eventMapper = eventMapper;
        this.permissionRequestMapper = permissionRequestMapper;
        this.permissionDecisionMapper = permissionDecisionMapper;
        this.pendingPermissionRegistry = pendingPermissionRegistry;
        this.changeSetAccumulator = changeSetAccumulator;
        this.eventAggregator = eventAggregator;
        this.sessionEventEmitter = sessionEventEmitter;
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
        params.put("approvalPolicy", "on-request");
        JsonNode response = await(rpcClient.request(CodexProtocolConstants.METHOD_THREAD_START, params),
                CodexProtocolConstants.METHOD_THREAD_START);
        String nativeSessionId = response.path("thread").path("id").asText(null);
        if (nativeSessionId == null || nativeSessionId.isBlank()) {
            throw new AgentProtocolException("thread/start response did not contain thread.id: " + response);
        }
        String platformSessionId = request.platformSessionId() == null || request.platformSessionId().isBlank()
                ? UUID.randomUUID().toString() : request.platformSessionId();
        CodexSessionContext context = new CodexSessionContext(platformSessionId, nativeSessionId, request.tenantId(),
                request.userId(), request.deviceId(), request.projectId(), workspace.toString(), AgentType.CODEX);
        platformSessions.put(platformSessionId, context);
        nativeSessions.put(nativeSessionId, context);
        String platformCommandId = stringMetadata(request, AgentEventExtensionKeys.PLATFORM_COMMAND_ID);
        emit(new AgentEvent(null, UUID.randomUUID().toString(), request.tenantId(), request.userId(),
                request.deviceId(), request.projectId(), platformSessionId, 0, AgentType.CODEX,
                com.wangbin.ai.agent.contract.enums.AgentEventType.SESSION_STARTED, null, null,
                new SessionPayload(nativeSessionId, AgentSessionStatus.RUNNING, null,
                        Map.of("source", CodexProtocolConstants.METHOD_THREAD_START)),
                platformCommandId == null
                        ? Map.of(AgentEventExtensionKeys.NATIVE_METHOD, CodexProtocolConstants.METHOD_THREAD_START)
                        : Map.of(AgentEventExtensionKeys.NATIVE_METHOD, CodexProtocolConstants.METHOD_THREAD_START,
                        AgentEventExtensionKeys.PLATFORM_COMMAND_ID, platformCommandId)), context);
        return new AgentSession(platformSessionId, nativeSessionId, request.tenantId(), request.userId(),
                request.deviceId(), request.projectId(), AgentType.CODEX, AgentSessionStatus.RUNNING,
                capabilities(), Instant.now(), Map.of("workspacePath", workspace.toString()));
    }

    @Override
    public void sendPrompt(String sessionId, PromptCommand command) {
        CodexSessionContext context = requireSession(sessionId);
        if (!context.beginPlatformCommand(command.commandId())) {
            throw new AgentSessionException("session already has an active Codex turn: " + sessionId);
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", context.nativeSessionId());
        params.put("cwd", context.workspacePath());
        params.put("clientUserMessageId", command.commandId());
        ArrayNode input = params.putArray("input");
        ObjectNode text = input.addObject();
        text.put("type", "text");
        text.put("text", command.prompt());
        try {
            JsonNode response = await(rpcClient.request(CodexProtocolConstants.METHOD_TURN_START, params),
                    CodexProtocolConstants.METHOD_TURN_START);
            String turnId = response.path("turn").path("id").asText(null);
            if (turnId != null) {
                activeTurnIds.put(context.platformSessionId(), turnId);
            }
        } catch (RuntimeException ex) {
            context.clearPlatformCommand(command.commandId());
            throw ex;
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
        await(rpcClient.request(CodexProtocolConstants.METHOD_TURN_INTERRUPT, params),
                CodexProtocolConstants.METHOD_TURN_INTERRUPT);
    }

    @Override
    public void resolvePermission(String sessionId, String permissionId, PermissionDecision decision,
                                  String decisionCommandId) {
        CodexSessionContext context = requireSession(sessionId);
        PendingPermission pending = pendingPermissionRegistry.findByPermissionId(permissionId)
                .orElseThrow(() -> new AgentCapabilityException("permission is not pending: " + permissionId));
        if (!context.platformSessionId().equals(pending.platformSessionId())) {
            throw new AgentCapabilityException("permission does not belong to session: " + permissionId);
        }
        permissionRequestMapper.validateStoredWorkspace(pending, workspaceManager);
        CodexPermissionDecisionAttempt attempt = pending.beginDecision(decision, decisionCommandId);
        if (attempt == CodexPermissionDecisionAttempt.DUPLICATE) {
            return;
        }
        if (attempt == CodexPermissionDecisionAttempt.UNSUPPORTED_DECISION) {
            throw new AgentCapabilityException("permission decision is not supported by native request");
        }
        if (attempt == CodexPermissionDecisionAttempt.NOT_PENDING) {
            throw new AgentCapabilityException("permission is not pending: " + permissionId);
        }
        try {
            rpcClient.respond(pending.nativeRequestId(), permissionDecisionMapper.responseResult(decision));
            pending.markDecisionSent(decisionCommandId);
        } catch (RuntimeException ex) {
            pending.rollbackDecisionAttempt(decisionCommandId);
            throw ex;
        }
    }

    @Override
    public void cancelPendingPermissions(String sessionId) {
        pendingPermissionRegistry.removeBySession(sessionId).forEach(permission -> {
            CodexJsonRpcClient client = rpcClient;
            if (client != null && !client.isClosed()) {
                try {
                    client.respond(permission.nativeRequestId(),
                            permissionDecisionMapper.responseResult(PermissionDecision.CANCELLED));
                } catch (RuntimeException ignored) {
                    // Runtime shutdown may already have closed stdin; local cleanup remains authoritative.
                }
            }
        });
    }

    @Override
    public Flux<AgentEvent> events(String sessionId) {
        return eventSink.asFlux().filter(event -> sessionId.equals(event.sessionId()));
    }

    @Override
    public void closeSession(String sessionId) {
        closeSession(sessionId, null);
    }

    @Override
    public void closeSession(String sessionId, String controlCommandId) {
        CodexSessionContext removed = platformSessions.remove(sessionId);
        if (removed != null) {
            eventAggregator.closeSession(removed.platformSessionId(), removed::nextSeq,
                            event -> emitSequenced(event, removed))
                    .forEach(event -> emitSequenced(event, removed));
            emit(sessionCompletedEvent(removed, controlCommandId), removed);
            changeSetAccumulator.clearSession(removed.platformSessionId());
            sessionEventEmitter.releaseSession(removed.platformSessionId());
            nativeSessions.remove(removed.nativeSessionId());
            activeTurnIds.remove(sessionId);
            cancelPendingPermissions(sessionId);
        }
        if (platformSessions.isEmpty() && managedProcess != null) {
            stopRuntime();
        }
    }

    @Override
    public void emitSessionControlTimeout(String sessionId, String targetCommandId, String controlCommandId,
                                          SessionControlAction action, String reason) {
        CodexSessionContext context = platformSessions.get(sessionId);
        if (context != null) {
            emit(sessionControlTimeoutEvent(context, targetCommandId, controlCommandId, action, reason), context);
        }
    }

    private synchronized void ensureStarted(Path workspace) {
        if (runtimeState == CodexRuntimeState.READY
                && managedProcess != null
                && managedProcess.isAlive()
                && rpcClient != null
                && !rpcClient.isClosed()) {
            return;
        }
        if (runtimeState != CodexRuntimeState.STOPPED) {
            cleanupRuntime(CodexRuntimeState.CRASHED);
        }
        try {
            runtimeState = CodexRuntimeState.STARTING;
            managedProcess = appServerProcess.start(workspace);
            rpcClient = createRpcClient(managedProcess);
            rpcClient.messages().subscribe(this::handleMessage);
            rpcClient.protocolIssues().subscribe(issue -> {
                // Protocol issues are diagnostics and must not be guessed into an unrelated session.
            });
            rpcClient.start();
            runtimeState = CodexRuntimeState.INITIALIZING;
            initialize();
            runtimeState = CodexRuntimeState.READY;
        } catch (RuntimeException ex) {
            cleanupRuntime(CodexRuntimeState.CRASHED);
            throw ex;
        }
    }

    protected CodexJsonRpcClient createRpcClient(ManagedProcess process) {
        return new CodexJsonRpcClient(objectMapper, process.stdout(),
                new OutputStreamWriter(process.process().getOutputStream(), StandardCharsets.UTF_8),
                processIoExecutor, codexProperties.getRequestTimeout());
    }

    private void initialize() {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "ai_coding_remote");
        clientInfo.put("title", "AI Coding Remote");
        clientInfo.put("version", "0.1.0");
        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.put("experimentalApi", codexProperties.isExperimentalApi());
        await(rpcClient.request(CodexProtocolConstants.METHOD_INITIALIZE, params),
                CodexProtocolConstants.METHOD_INITIALIZE);
        rpcClient.notify(CodexProtocolConstants.METHOD_INITIALIZED, null);
    }

    void handleMessage(CodexRpcMessage message) {
        CodexSessionContext context = resolveContext(message.params());
        if (message.kind() == CodexRpcMessageKind.SERVER_REQUEST) {
            handleServerRequest(message, context);
            return;
        }
        if (message.kind() == CodexRpcMessageKind.NOTIFICATION
                && CodexProtocolConstants.METHOD_SERVER_REQUEST_RESOLVED.equals(message.method())) {
            handleServerRequestResolved(message);
            return;
        }
        captureActiveTurn(context, message.params());
        for (AgentEvent event : eventMapper.map(message, context)) {
            emit(event, context);
        }
    }

    private void handleServerRequest(CodexRpcMessage message, CodexSessionContext context) {
        if (!CodexProtocolConstants.APPROVAL_REQUEST_METHODS.contains(message.method())) {
            rpcClient.protocolWarning("unknown_server_request",
                    "unknown Codex server request method: " + message.method(), null);
            rpcClient.respondError(message.id(), CodexProtocolConstants.JSON_RPC_METHOD_NOT_FOUND,
                    "Unsupported server request method: " + message.method());
            return;
        }
        if (context == null) {
            rpcClient.protocolWarning("unroutable_server_request",
                    "Codex server request has no resolvable session: " + message.method(), null);
            rpcClient.respondError(message.id(), CodexProtocolConstants.JSON_RPC_ROUTE_UNAVAILABLE,
                    "Unable to route server request to a platform session");
            return;
        }
        captureActiveTurn(context, message.params());
        if (!permissionRequestMapper.isSupportedApprovalMethod(message.method())) {
            rpcClient.protocolWarning("unsupported_permission_request",
                    "unsupported Codex approval request method: " + message.method(), null);
            rpcClient.respondError(message.id(), CodexProtocolConstants.JSON_RPC_METHOD_NOT_FOUND,
                    "Unsupported approval request method: " + message.method());
            emit(warningEvent(context, "Unsupported approval request method: " + message.method()), context);
            return;
        }
        try {
            PendingPermission pending = permissionRequestMapper.toPendingPermission(message, context, workspaceManager);
            CodexPendingPermissionRegistry.RegistrationResult result = pendingPermissionRegistry.register(pending);
            if (result.created()) {
                emit(permissionRequiredEvent(context, result.permission()), context);
            }
        } catch (CodexPendingPermissionCapacityException ex) {
            rpcClient.respond(message.id(), permissionDecisionMapper.responseResult(PermissionDecision.REJECTED));
            emit(warningEvent(context, "Pending approval capacity exceeded; request declined locally"), context);
        } catch (AgentCapabilityException ex) {
            rpcClient.respond(message.id(), permissionDecisionMapper.responseResult(PermissionDecision.REJECTED));
            emit(warningEvent(context, "Approval request rejected by local workspace policy"), context);
        }
    }

    private void handleServerRequestResolved(CodexRpcMessage message) {
        JsonNode nativeRequestId = nativeRequestId(message.params());
        if (nativeRequestId == null) {
            rpcClient.protocolWarning("unroutable_server_request_resolved",
                    "serverRequest/resolved did not contain native request id", null);
            return;
        }
        pendingPermissionRegistry.resolveByNativeRequestId(nativeRequestId).ifPresentOrElse(permission -> {
            CodexSessionContext context = platformSessions.get(permission.platformSessionId());
            if (context != null) {
                emit(permissionResolvedEvent(context, permission), context);
            }
        }, () -> rpcClient.protocolWarning("unknown_server_request_resolved",
                "serverRequest/resolved had no pending platform permission", null));
    }

    private void emit(AgentEvent event, CodexSessionContext context) {
        Consumer<AgentEvent> directEmitter = timed -> emitSequenced(timed, context);
        for (AgentEvent changeEvent : changeSetAccumulator.accept(event, context)) {
            for (AgentEvent aggregated : eventAggregator.accept(changeEvent, context::nextSeq, directEmitter)) {
                emitSequenced(aggregated, context);
            }
        }
    }

    private void emitSequenced(AgentEvent event, CodexSessionContext context) {
        sessionEventEmitter.emit(event, context::nextSeq, this::emitDirect);
        clearCompletedPlatformCommand(event, context);
    }

    private void emitDirect(AgentEvent event) {
        if (event != null) {
            eventSink.tryEmitNext(event);
        }
    }

    private AgentEvent permissionRequiredEvent(CodexSessionContext context, PendingPermission pending) {
        return platformEvent(context, com.wangbin.ai.agent.contract.enums.AgentEventType.PERMISSION_REQUIRED,
                new PermissionRequiredPayload(pending.permissionId(), pending.permissionType(), pending.title(),
                        pending.reason(), pending.detail(), pending.extensions()));
    }

    private AgentEvent permissionResolvedEvent(CodexSessionContext context, PendingPermission pending) {
        return platformEvent(context, com.wangbin.ai.agent.contract.enums.AgentEventType.PERMISSION_RESOLVED,
                new PermissionResolvedPayload(pending.permissionId(), pending.permissionType(), pending.decision(),
                        pending.resolutionStatus(), pending.decisionCommandId(), pending.resolvedAt(), null,
                        pending.extensions()));
    }

    private AgentEvent sessionCompletedEvent(CodexSessionContext context, String controlCommandId) {
        Map<String, Object> extensions = controlCommandId == null || controlCommandId.isBlank()
                ? Map.of(AgentEventExtensionKeys.NATIVE_METHOD, "local/closeSession")
                : Map.of(AgentEventExtensionKeys.NATIVE_METHOD, "local/closeSession",
                AgentEventExtensionKeys.PLATFORM_COMMAND_ID, controlCommandId);
        return new AgentEvent(null, null, context.tenantId(), context.userId(), context.deviceId(),
                context.projectId(), context.platformSessionId(), 0, context.agentType(), AgentEventType.SESSION_COMPLETED,
                null, null, new SessionPayload(context.nativeSessionId(), AgentSessionStatus.COMPLETED,
                "session closed locally", Map.of()), extensions);
    }

    private AgentEvent sessionControlTimeoutEvent(CodexSessionContext context, String targetCommandId,
                                                  String controlCommandId, SessionControlAction action,
                                                  String reason) {
        Map<String, Object> extensions = targetCommandId == null || targetCommandId.isBlank()
                ? Map.of()
                : Map.of(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, targetCommandId);
        return new AgentEvent(null, null, context.tenantId(), context.userId(), context.deviceId(),
                context.projectId(), context.platformSessionId(), 0, context.agentType(),
                AgentEventType.SESSION_CONTROL_TIMEOUT, null, null,
                new SessionControlTimeoutPayload(targetCommandId, controlCommandId, action, Instant.now(), reason,
                        Map.of()),
                extensions);
    }

    private AgentEvent warningEvent(CodexSessionContext context, String message) {
        return platformEvent(context, com.wangbin.ai.agent.contract.enums.AgentEventType.WARNING,
                new WarningPayload(message, Map.of(AgentEventExtensionKeys.NATIVE_METHOD, "approval")));
    }

    private AgentEvent platformEvent(CodexSessionContext context,
                                     com.wangbin.ai.agent.contract.enums.AgentEventType type,
                                     AgentEventPayload payload) {
        Map<String, Object> eventExtensions = context.activePlatformCommandId() == null
                ? Map.of() : Map.of(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, context.activePlatformCommandId());
        return new AgentEvent(null, null, context.tenantId(), context.userId(), context.deviceId(),
                context.projectId(), context.platformSessionId(), 0, context.agentType(), type, null, null,
                payload, eventExtensions);
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

    private JsonNode nativeRequestId(JsonNode params) {
        if (params == null || params.isNull()) {
            return null;
        }
        JsonNode id = params.get("requestId");
        if (id == null || id.isNull()) {
            id = params.get("id");
        }
        if (id == null || id.isNull()) {
            JsonNode request = params.get("request");
            id = request == null || request.isNull() ? null : request.get("id");
        }
        return id == null || id.isNull() ? null : id.deepCopy();
    }

    private CodexSessionContext requireSession(String sessionId) {
        CodexSessionContext context = platformSessions.get(sessionId);
        if (context == null) {
            throw new AgentSessionException("unknown Codex session: " + sessionId);
        }
        return context;
    }

    private void clearCompletedPlatformCommand(AgentEvent event, CodexSessionContext context) {
        Object commandId = event.extensions().get(AgentEventExtensionKeys.PLATFORM_COMMAND_ID);
        if (!(commandId instanceof String text) || text.isBlank()) {
            return;
        }
        if (AgentCommandLifecyclePolicy.isTerminalForActiveCommand(event)) {
            context.clearPlatformCommand(text);
            activeTurnIds.remove(context.platformSessionId());
        }
    }

    private String stringMetadata(SessionStartRequest request, String key) {
        Object value = request.metadata().get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private JsonNode await(java.util.concurrent.CompletableFuture<JsonNode> future, String operation) {
        try {
            return future.get(codexProperties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentProtocolException("Codex operation interrupted: " + operation, ex);
        } catch (TimeoutException ex) {
            throw new AgentProtocolException("Codex operation timed out: " + operation, ex);
        } catch (ExecutionException ex) {
            Throwable cause = unwrap(ex);
            throw new AgentProtocolException("Codex operation failed: " + operation
                    + ", cause=" + exceptionSummary(cause), ex);
        }
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String exceptionSummary(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + truncate(message);
    }

    private String truncate(String value) {
        return value.length() <= ERROR_SUMMARY_MAX_LENGTH ? value
                : value.substring(0, ERROR_SUMMARY_MAX_LENGTH) + "...";
    }

    synchronized CodexRuntimeState runtimeState() {
        return runtimeState;
    }

    private synchronized void stopRuntime() {
        if (runtimeState == CodexRuntimeState.STOPPED) {
            return;
        }
        runtimeState = CodexRuntimeState.STOPPING;
        cleanupRuntime(CodexRuntimeState.STOPPED);
    }

    private void cleanupRuntime(CodexRuntimeState finalState) {
        CodexJsonRpcClient client = rpcClient;
        ManagedProcess process = managedProcess;
        rpcClient = null;
        managedProcess = null;
        if (client != null) {
            client.closeWithError(new AgentConnectionException("codex runtime stopped", null));
        }
        if (process != null) {
            process.close();
        }
        platformSessions.clear();
        nativeSessions.clear();
        activeTurnIds.clear();
        pendingPermissionRegistry.clear();
        changeSetAccumulator.clear();
        runtimeState = finalState == CodexRuntimeState.CRASHED ? CodexRuntimeState.STOPPED : finalState;
    }

}
