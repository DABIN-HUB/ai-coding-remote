package com.wangbin.ai.agent.daemon.command;

import com.wangbin.ai.agent.contract.command.*;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.contract.enums.SessionControlAction;
import com.wangbin.ai.agent.contract.enums.SessionInterruptInitiator;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentEventExtensionKeys;
import com.wangbin.ai.agent.contract.event.AgentEventPayload;
import com.wangbin.ai.agent.contract.event.SessionInterruptedPayload;
import com.wangbin.ai.agent.contract.session.PromptCommand;
import com.wangbin.ai.agent.contract.session.SessionStartRequest;
import com.wangbin.ai.agent.daemon.adapter.CodingAgentAdapter;
import com.wangbin.ai.agent.daemon.artifact.ArtifactTransferManager;
import com.wangbin.ai.agent.daemon.artifact.ArtifactTransferManager.PendingArtifactTransfer;
import com.wangbin.ai.agent.daemon.cloud.relay.DaemonOutboundSender;
import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.event.AgentCommandLifecyclePolicy;
import com.wangbin.ai.agent.daemon.project.LocalProject;
import com.wangbin.ai.agent.daemon.project.LocalProjectRegistry;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class DefaultAgentCommandHandler implements AgentCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentCommandHandler.class);

    private static final String CODE_ACCEPTED = "ACCEPTED";
    private static final String CODE_DUPLICATE = "DUPLICATE";
    private static final String CODE_REJECTED = "REJECTED";
    private static final String CODE_BUSY = "SESSION_BUSY";
    private static final String CODE_ARTIFACT_BUSY = "ARTIFACT_TRANSFER_BUSY";
    private static final String CODE_FAILED = "COMMAND_START_FAILED";
    private static final String CODE_PERMISSION_FAILED = "PERMISSION_DECISION_FAILED";
    private static final String CODE_TARGET_NOT_ACTIVE = "TARGET_COMMAND_NOT_ACTIVE";
    private static final String CODE_CONTROL_CONFLICT = "SESSION_CONTROL_CONFLICT";
    private static final String CODE_CONTROL_FAILED = "SESSION_CONTROL_FAILED";

    private final List<CodingAgentAdapter> adapters;
    private final LocalProjectRegistry localProjectRegistry;
    private final CommandDedupCache dedupCache;
    private final WorkspaceManager workspaceManager;
    private final ArtifactTransferManager artifactTransferManager;
    private final SessionControlIntentRegistry controlIntentRegistry;
    private final ScheduledExecutorService eventScheduler;
    private final ConcurrentMap<String, CodingAgentAdapter> sessionAdapters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeSessionCommands = new ConcurrentHashMap<>();

    @Autowired
    public DefaultAgentCommandHandler(List<CodingAgentAdapter> adapters, LocalProjectRegistry localProjectRegistry,
                                      CommandDedupCache dedupCache, WorkspaceManager workspaceManager,
                                      ArtifactTransferManager artifactTransferManager,
                                      SessionControlIntentRegistry controlIntentRegistry,
                                      @Qualifier("agentEventScheduler") ScheduledExecutorService eventScheduler) {
        this.adapters = adapters;
        this.localProjectRegistry = localProjectRegistry;
        this.dedupCache = dedupCache;
        this.workspaceManager = workspaceManager;
        this.artifactTransferManager = artifactTransferManager;
        this.controlIntentRegistry = controlIntentRegistry;
        this.eventScheduler = eventScheduler;
    }

    DefaultAgentCommandHandler(List<CodingAgentAdapter> adapters, LocalProjectRegistry localProjectRegistry,
                               CommandDedupCache dedupCache, WorkspaceManager workspaceManager) {
        this(adapters, localProjectRegistry, dedupCache, workspaceManager, null,
                new SessionControlIntentRegistry(128), null);
    }

    DefaultAgentCommandHandler(List<CodingAgentAdapter> adapters, LocalProjectRegistry localProjectRegistry,
                               CommandDedupCache dedupCache, WorkspaceManager workspaceManager,
                               ArtifactTransferManager artifactTransferManager) {
        this(adapters, localProjectRegistry, dedupCache, workspaceManager, artifactTransferManager,
                new SessionControlIntentRegistry(128), null);
    }

    @Override
    public void handle(AgentCommand command, DeviceCredentialState credential, DaemonOutboundSender outboundSender) {
        if (!isIdentityValid(command, credential)) {
            outboundSender.sendCommandAck(rejected(command, "command identity does not match daemon credential"));
            return;
        }
        if (command.commandType() == CommandType.PROMPT) {
            handlePrompt(command, outboundSender);
        } else if (command.commandType() == CommandType.APPROVE_PERMISSION
                || command.commandType() == CommandType.REJECT_PERMISSION) {
            handlePermissionDecision(command, outboundSender);
        } else if (command.commandType() == CommandType.FETCH_ARTIFACT) {
            handleFetchArtifact(command, credential, outboundSender);
        } else if (command.commandType() == CommandType.INTERRUPT || command.commandType() == CommandType.CANCEL
                || command.commandType() == CommandType.CLOSE_SESSION) {
            handleSessionControl(command, outboundSender);
        } else {
            outboundSender.sendCommandAck(rejected(command, "unsupported command type"));
        }
    }

    private void handlePrompt(AgentCommand command, DaemonOutboundSender outboundSender) {
        if (!(command.payload() instanceof PromptCommandPayload payload)) {
            outboundSender.sendCommandAck(rejected(command, "invalid prompt payload"));
            return;
        }
        LocalProject project = localProjectRegistry.findByPlatformProjectId(command.projectId()).orElse(null);
        if (project == null || project.agentType() != command.agentType()) {
            outboundSender.sendCommandAck(rejected(command, "project is not registered locally"));
            return;
        }
        Path realWorkspace;
        try {
            realWorkspace = workspaceManager.validateWorkspace(project.realWorkspace().toString());
            if (!realWorkspace.equals(project.realWorkspace())) {
                outboundSender.sendCommandAck(rejected(command, "workspace real path changed"));
                return;
            }
        } catch (AgentCapabilityException ex) {
            outboundSender.sendCommandAck(rejected(command, "workspace is not allowed locally"));
            return;
        }
        CodingAgentAdapter adapter = adapter(command.agentType());
        if (adapter == null || !adapter.capabilities().prompt()) {
            outboundSender.sendCommandAck(rejected(command, "agent adapter does not support prompt"));
            return;
        }
        if (dedupCache.reserve(command.commandId()) == CommandDedupResult.DUPLICATE) {
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.DUPLICATE, CODE_DUPLICATE, "duplicate command", Instant.now(), Map.of()));
            return;
        }
        String activeCommand = activeSessionCommands.putIfAbsent(command.sessionId(), command.commandId());
        if (activeCommand != null) {
            dedupCache.release(command.commandId());
            outboundSender.sendCommandAck(busy(command));
            return;
        }
        CommandAck accepted = new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                CommandAckStatus.ACCEPTED, CODE_ACCEPTED, "command accepted", Instant.now(), Map.of());
        if (!outboundSender.sendCommandAck(accepted)) {
            activeSessionCommands.remove(command.sessionId(), command.commandId());
            dedupCache.release(command.commandId());
            return;
        }
        try {
            CodingAgentAdapter activeAdapter = sessionAdapters.computeIfAbsent(command.sessionId(), ignored -> {
                adapter.startSession(new SessionStartRequest(command.sessionId(), command.tenantId(), command.userId(),
                        command.deviceId(), command.projectId(), realWorkspace.toString(), command.agentType(),
                        Map.of(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, command.commandId())));
                adapter.events(command.sessionId()).subscribe(event -> handleAgentEvent(outboundSender, event));
                return adapter;
            });
            activeAdapter.sendPrompt(command.sessionId(), new PromptCommand(command.commandId(), payload.prompt(), Map.of()));
            dedupCache.markCompleted(command.commandId());
        } catch (RuntimeException ex) {
            activeSessionCommands.remove(command.sessionId(), command.commandId());
            log.warn("command execution failed to start: sessionId={}, commandId={}, errorType={}, error={}",
                    command.sessionId(), command.commandId(), ex.getClass().getSimpleName(), ex.getMessage());
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.FAILED, CODE_FAILED, "command execution failed to start", Instant.now(),
                    Map.of()));
        }
    }

    private void handlePermissionDecision(AgentCommand command, DaemonOutboundSender outboundSender) {
        if (!(command.payload() instanceof PermissionDecisionCommandPayload payload)
                || !isPermissionDecisionConsistent(command.commandType(), payload.decision())) {
            outboundSender.sendCommandAck(rejected(command, "invalid permission decision payload"));
            return;
        }
        LocalProject project = localProjectRegistry.findByPlatformProjectId(command.projectId()).orElse(null);
        if (project == null || project.agentType() != command.agentType()) {
            outboundSender.sendCommandAck(rejected(command, "project is not registered locally"));
            return;
        }
        try {
            Path realWorkspace = workspaceManager.validateWorkspace(project.realWorkspace().toString());
            if (!realWorkspace.equals(project.realWorkspace())) {
                outboundSender.sendCommandAck(rejected(command, "workspace real path changed"));
                return;
            }
        } catch (AgentCapabilityException ex) {
            outboundSender.sendCommandAck(rejected(command, "workspace is not allowed locally"));
            return;
        }
        CodingAgentAdapter adapter = sessionAdapters.get(command.sessionId());
        if (adapter == null || !adapter.capabilities().permission()) {
            outboundSender.sendCommandAck(rejected(command, "agent adapter does not support permission decision"));
            return;
        }
        if (dedupCache.reserve(command.commandId()) == CommandDedupResult.DUPLICATE) {
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.DUPLICATE, CODE_DUPLICATE, "duplicate command", Instant.now(), Map.of()));
            return;
        }
        try {
            adapter.resolvePermission(command.sessionId(), payload.permissionId(), payload.decision(),
                    command.commandId());
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.ACCEPTED, CODE_ACCEPTED, "permission decision accepted", Instant.now(),
                    Map.of()));
            dedupCache.markCompleted(command.commandId());
        } catch (RuntimeException ex) {
            log.warn("permission decision failed: sessionId={}, commandId={}, errorType={}, error={}",
                    command.sessionId(), command.commandId(), ex.getClass().getSimpleName(), ex.getMessage());
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.FAILED, CODE_PERMISSION_FAILED, "permission decision failed", Instant.now(),
                    Map.of()));
        }
    }

    private void handleFetchArtifact(AgentCommand command, DeviceCredentialState credential,
                                     DaemonOutboundSender outboundSender) {
        if (artifactTransferManager == null) {
            outboundSender.sendCommandAck(rejected(command, "artifact transfer is not configured"));
            return;
        }
        if (!(command.payload() instanceof ArtifactFetchCommandPayload payload)) {
            outboundSender.sendCommandAck(rejected(command, "invalid artifact fetch payload"));
            return;
        }
        LocalProject project = localProjectRegistry.findByPlatformProjectId(command.projectId()).orElse(null);
        if (project == null || project.agentType() != command.agentType()) {
            outboundSender.sendCommandAck(rejected(command, "project is not registered locally"));
            return;
        }
        try {
            Path realWorkspace = workspaceManager.validateWorkspace(project.realWorkspace().toString());
            if (!realWorkspace.equals(project.realWorkspace())) {
                outboundSender.sendCommandAck(rejected(command, "workspace real path changed"));
                return;
            }
            artifactTransferManager.canResolve(command, payload);
        } catch (AgentCapabilityException ex) {
            outboundSender.sendCommandAck(rejected(command, "artifact source is not allowed locally"));
            return;
        }
        if (activeSessionCommands.containsKey(command.sessionId())) {
            outboundSender.sendCommandAck(busy(command));
            return;
        }
        if (dedupCache.reserve(command.commandId()) == CommandDedupResult.DUPLICATE) {
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.DUPLICATE, CODE_DUPLICATE, "duplicate command", Instant.now(), Map.of()));
            return;
        }
        PendingArtifactTransfer transfer = artifactTransferManager.submit(command, payload, credential);
        if (transfer == null) {
            dedupCache.release(command.commandId());
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.REJECTED, CODE_ARTIFACT_BUSY, "artifact transfer queue is full", Instant.now(),
                    Map.of()));
            return;
        }
        CommandAck accepted = new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                CommandAckStatus.ACCEPTED, CODE_ACCEPTED, "artifact fetch accepted", Instant.now(), Map.of());
        if (!outboundSender.sendCommandAck(accepted)) {
            transfer.cancel();
            dedupCache.release(command.commandId());
            return;
        }
        transfer.start();
        dedupCache.markCompleted(command.commandId());
    }

    private void handleSessionControl(AgentCommand command, DaemonOutboundSender outboundSender) {
        SessionControlRequest request = sessionControlRequest(command);
        if (request == null) {
            outboundSender.sendCommandAck(rejected(command, "invalid session control payload"));
            return;
        }
        LocalProject project = localProjectRegistry.findByPlatformProjectId(command.projectId()).orElse(null);
        if (project == null || project.agentType() != command.agentType()) {
            outboundSender.sendCommandAck(rejected(command, "project is not registered locally"));
            return;
        }
        try {
            Path realWorkspace = workspaceManager.validateWorkspace(project.realWorkspace().toString());
            if (!realWorkspace.equals(project.realWorkspace())) {
                outboundSender.sendCommandAck(rejected(command, "workspace real path changed"));
                return;
            }
        } catch (AgentCapabilityException ex) {
            outboundSender.sendCommandAck(rejected(command, "workspace is not allowed locally"));
            return;
        }
        CodingAgentAdapter adapter = sessionAdapters.get(command.sessionId());
        if (adapter == null) {
            outboundSender.sendCommandAck(rejected(command, "session is not active locally"));
            return;
        }
        String activeCommandId = activeSessionCommands.get(command.sessionId());
        String targetCommandId = request.targetCommandId();
        if (request.action() == SessionControlAction.CLOSE_SESSION && isBlank(targetCommandId)) {
            targetCommandId = activeCommandId;
        }
        if (request.action() != SessionControlAction.CLOSE_SESSION && isBlank(targetCommandId)) {
            outboundSender.sendCommandAck(rejected(command, "target command is required"));
            return;
        }
        if (request.action() != SessionControlAction.CLOSE_SESSION
                && !targetCommandId.equals(activeCommandId)) {
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.REJECTED, CODE_TARGET_NOT_ACTIVE, "target command is not active",
                    Instant.now(), Map.of()));
            return;
        }
        if (request.action() == SessionControlAction.CLOSE_SESSION && activeCommandId != null
                && !activeCommandId.equals(targetCommandId)) {
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.REJECTED, CODE_TARGET_NOT_ACTIVE, "target command is not active",
                    Instant.now(), Map.of()));
            return;
        }
        if (dedupCache.reserve(command.commandId()) == CommandDedupResult.DUPLICATE) {
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.DUPLICATE, CODE_DUPLICATE, "duplicate command", Instant.now(), Map.of()));
            return;
        }
        if (activeCommandId == null && request.action() == SessionControlAction.CLOSE_SESSION) {
            closeIdleSession(command, adapter, outboundSender);
            return;
        }
        SessionControlReserveResult reserveResult = controlIntentRegistry.reserve(command.sessionId(),
                targetCommandId, request.action(), command.commandId(), request.reason());
        if (reserveResult == SessionControlReserveResult.DUPLICATE) {
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.DUPLICATE, CODE_DUPLICATE, "duplicate command", Instant.now(), Map.of()));
            dedupCache.markCompleted(command.commandId());
            return;
        }
        if (reserveResult != SessionControlReserveResult.RESERVED) {
            dedupCache.release(command.commandId());
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.REJECTED, CODE_CONTROL_CONFLICT, "session control command conflicts",
                    Instant.now(), Map.of()));
            return;
        }
        try {
            adapter.cancelPendingPermissions(command.sessionId());
            adapter.interrupt(command.sessionId());
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.ACCEPTED, CODE_ACCEPTED, "session control accepted", Instant.now(), Map.of()));
            dedupCache.markCompleted(command.commandId());
            scheduleControlTimeoutCheck(command.sessionId(), targetCommandId);
        } catch (RuntimeException ex) {
            controlIntentRegistry.consume(command.sessionId(), targetCommandId);
            log.warn("session control failed: sessionId={}, commandId={}, errorType={}, error={}",
                    command.sessionId(), command.commandId(), ex.getClass().getSimpleName(), ex.getMessage());
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.FAILED, CODE_CONTROL_FAILED, "session control failed", Instant.now(), Map.of()));
        }
    }

    private void scheduleControlTimeoutCheck(String sessionId, String targetCommandId) {
        if (eventScheduler == null) {
            return;
        }
        long delayMillis = controlIntentRegistry.terminalTimeoutMillis();
        eventScheduler.schedule(() -> emitTimedOutControls(sessionId, targetCommandId),
                delayMillis, TimeUnit.MILLISECONDS);
    }

    private void emitTimedOutControls(String sessionId, String targetCommandId) {
        controlIntentRegistry.timeoutIfExpired(sessionId, targetCommandId, Instant.now()).ifPresent(intent -> {
            CodingAgentAdapter adapter = sessionAdapters.get(sessionId);
            if (adapter != null) {
                adapter.emitSessionControlTimeout(sessionId, intent.targetCommandId(), intent.controlCommandId(),
                        intent.action(), "session control terminal timeout");
            }
        });
    }

    private void closeIdleSession(AgentCommand command, CodingAgentAdapter adapter, DaemonOutboundSender outboundSender) {
        try {
            adapter.cancelPendingPermissions(command.sessionId());
            CommandAck accepted = new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.ACCEPTED, CODE_ACCEPTED, "session close accepted", Instant.now(), Map.of());
            if (!outboundSender.sendCommandAck(accepted)) {
                dedupCache.release(command.commandId());
                return;
            }
            adapter.closeSession(command.sessionId(), command.commandId());
            sessionAdapters.remove(command.sessionId(), adapter);
            controlIntentRegistry.clearSession(command.sessionId());
            dedupCache.markCompleted(command.commandId());
        } catch (RuntimeException ex) {
            log.warn("idle session close failed: sessionId={}, commandId={}, errorType={}, error={}",
                    command.sessionId(), command.commandId(), ex.getClass().getSimpleName(), ex.getMessage());
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.FAILED, CODE_CONTROL_FAILED, "session close failed", Instant.now(), Map.of()));
        }
    }

    private void handleAgentEvent(DaemonOutboundSender outboundSender, AgentEvent event) {
        SessionControlApplied applied = applySessionControlIntent(event);
        outboundSender.sendAgentEvent(applied.event());
        if (AgentCommandLifecyclePolicy.isTerminalForActiveCommand(event)) {
            Object commandId = applied.event().extensions().get(AgentEventExtensionKeys.PLATFORM_COMMAND_ID);
            if (commandId instanceof String text && !text.isBlank()) {
                activeSessionCommands.remove(applied.event().sessionId(), text);
            }
            if (applied.event().type() == com.wangbin.ai.agent.contract.enums.AgentEventType.SESSION_COMPLETED) {
                sessionAdapters.remove(applied.event().sessionId());
                controlIntentRegistry.clearSession(applied.event().sessionId());
            }
        }
        if (applied.intent() != null && applied.intent().action() == SessionControlAction.CLOSE_SESSION) {
            CodingAgentAdapter adapter = sessionAdapters.get(applied.event().sessionId());
            if (adapter != null) {
                adapter.closeSession(applied.event().sessionId(), applied.intent().controlCommandId());
            }
        }
    }

    private SessionControlApplied applySessionControlIntent(AgentEvent event) {
        if (event.type() != com.wangbin.ai.agent.contract.enums.AgentEventType.SESSION_INTERRUPTED
                || !(event.payload() instanceof SessionInterruptedPayload payload)) {
            return new SessionControlApplied(event, null);
        }
        String targetCommandId = payload.targetCommandId();
        if (isBlank(targetCommandId)) {
            Object commandId = event.extensions().get(AgentEventExtensionKeys.PLATFORM_COMMAND_ID);
            targetCommandId = commandId instanceof String text ? text : null;
        }
        if (isBlank(targetCommandId)) {
            return new SessionControlApplied(event, null);
        }
        String resolvedTargetCommandId = targetCommandId;
        return controlIntentRegistry.consume(event.sessionId(), resolvedTargetCommandId).map(intent -> {
            SessionInterruptedPayload enriched = new SessionInterruptedPayload(payload.nativeSessionId(),
                    resolvedTargetCommandId, intent.controlCommandId(), intent.action(),
                    initiator(intent.action()), intent.reason(), payload.extensions());
            return new SessionControlApplied(copyPayload(event, enriched), intent);
        }).orElse(new SessionControlApplied(event, null));
    }

    private SessionInterruptInitiator initiator(SessionControlAction action) {
        return action == SessionControlAction.CLOSE_SESSION ? SessionInterruptInitiator.SESSION_CLOSE
                : SessionInterruptInitiator.USER;
    }

    private AgentEvent copyPayload(AgentEvent source, AgentEventPayload payload) {
        return new AgentEvent(source.eventId(), source.traceId(), source.tenantId(), source.userId(),
                source.deviceId(), source.projectId(), source.sessionId(), source.seq(), source.agentType(),
                source.type(), source.priority(), source.timestamp(), payload, source.extensions());
    }

    private SessionControlRequest sessionControlRequest(AgentCommand command) {
        if (command.commandType() == CommandType.INTERRUPT
                && command.payload() instanceof InterruptCommandPayload payload) {
            return new SessionControlRequest(SessionControlAction.INTERRUPT, payload.targetCommandId(),
                    payload.reason());
        }
        if (command.commandType() == CommandType.CANCEL && command.payload() instanceof CancelCommandPayload payload) {
            return new SessionControlRequest(SessionControlAction.CANCEL, payload.targetCommandId(), payload.reason());
        }
        if (command.commandType() == CommandType.CLOSE_SESSION
                && command.payload() instanceof CloseSessionCommandPayload payload) {
            return new SessionControlRequest(SessionControlAction.CLOSE_SESSION, payload.targetCommandId(),
                    payload.reason());
        }
        return null;
    }

    private boolean isPermissionDecisionConsistent(CommandType commandType,
                                                   com.wangbin.ai.agent.contract.enums.PermissionDecision decision) {
        if (commandType == CommandType.APPROVE_PERMISSION) {
            return decision == com.wangbin.ai.agent.contract.enums.PermissionDecision.APPROVED
                    || decision == com.wangbin.ai.agent.contract.enums.PermissionDecision.APPROVED_FOR_SESSION;
        }
        if (commandType == CommandType.REJECT_PERMISSION) {
            return decision == com.wangbin.ai.agent.contract.enums.PermissionDecision.REJECTED
                    || decision == com.wangbin.ai.agent.contract.enums.PermissionDecision.CANCELLED;
        }
        return false;
    }

    private boolean isIdentityValid(AgentCommand command, DeviceCredentialState credential) {
        return command != null
                && command.tenantId() != null
                && command.tenantId().equals(credential.getTenantId())
                && command.deviceId() != null
                && command.deviceId().equals(credential.getDeviceId());
    }

    private CodingAgentAdapter adapter(AgentType agentType) {
        return adapters.stream()
                .filter(candidate -> candidate.agentType() == agentType)
                .findFirst()
                .orElse(null);
    }

    private CommandAck rejected(AgentCommand command, String message) {
        String commandId = command == null ? null : command.commandId();
        String sessionId = command == null ? null : command.sessionId();
        String deviceId = command == null ? null : command.deviceId();
        return new CommandAck(commandId, sessionId, deviceId, CommandAckStatus.REJECTED, CODE_REJECTED, message,
                Instant.now(), Map.of());
    }

    private CommandAck busy(AgentCommand command) {
        return new CommandAck(command.commandId(), command.sessionId(), command.deviceId(), CommandAckStatus.REJECTED,
                CODE_BUSY, "session already has an active prompt command", Instant.now(), Map.of());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SessionControlRequest(SessionControlAction action, String targetCommandId, String reason) {
    }

    private record SessionControlApplied(AgentEvent event, SessionControlIntent intent) {
    }
}
