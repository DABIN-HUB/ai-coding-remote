package com.wangbin.ai.agent.daemon.command;

import com.wangbin.ai.agent.contract.command.*;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentEventExtensionKeys;
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
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    private final List<CodingAgentAdapter> adapters;
    private final LocalProjectRegistry localProjectRegistry;
    private final CommandDedupCache dedupCache;
    private final WorkspaceManager workspaceManager;
    private final ArtifactTransferManager artifactTransferManager;
    private final ConcurrentMap<String, CodingAgentAdapter> sessionAdapters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeSessionCommands = new ConcurrentHashMap<>();

    @Autowired
    public DefaultAgentCommandHandler(List<CodingAgentAdapter> adapters, LocalProjectRegistry localProjectRegistry,
                                      CommandDedupCache dedupCache, WorkspaceManager workspaceManager,
                                      ArtifactTransferManager artifactTransferManager) {
        this.adapters = adapters;
        this.localProjectRegistry = localProjectRegistry;
        this.dedupCache = dedupCache;
        this.workspaceManager = workspaceManager;
        this.artifactTransferManager = artifactTransferManager;
    }

    DefaultAgentCommandHandler(List<CodingAgentAdapter> adapters, LocalProjectRegistry localProjectRegistry,
                               CommandDedupCache dedupCache, WorkspaceManager workspaceManager) {
        this(adapters, localProjectRegistry, dedupCache, workspaceManager, null);
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

    private void handleAgentEvent(DaemonOutboundSender outboundSender, AgentEvent event) {
        outboundSender.sendAgentEvent(event);
        if (AgentCommandLifecyclePolicy.isTerminalForActiveCommand(event)) {
            Object commandId = event.extensions().get(AgentEventExtensionKeys.PLATFORM_COMMAND_ID);
            if (commandId instanceof String text && !text.isBlank()) {
                activeSessionCommands.remove(event.sessionId(), text);
            }
        }
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
}
