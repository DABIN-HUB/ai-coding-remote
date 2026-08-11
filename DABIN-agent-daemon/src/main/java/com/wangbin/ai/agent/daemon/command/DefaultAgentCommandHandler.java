package com.wangbin.ai.agent.daemon.command;

import com.wangbin.ai.agent.contract.command.*;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.contract.session.PromptCommand;
import com.wangbin.ai.agent.contract.session.SessionStartRequest;
import com.wangbin.ai.agent.daemon.adapter.CodingAgentAdapter;
import com.wangbin.ai.agent.daemon.cloud.relay.DaemonOutboundSender;
import com.wangbin.ai.agent.daemon.project.LocalProject;
import com.wangbin.ai.agent.daemon.project.LocalProjectRegistry;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class DefaultAgentCommandHandler implements AgentCommandHandler {

    private static final String CODE_ACCEPTED = "ACCEPTED";
    private static final String CODE_DUPLICATE = "DUPLICATE";
    private static final String CODE_REJECTED = "REJECTED";

    private final List<CodingAgentAdapter> adapters;
    private final LocalProjectRegistry localProjectRegistry;
    private final CommandDedupCache dedupCache;
    private final ConcurrentMap<String, CodingAgentAdapter> sessionAdapters = new ConcurrentHashMap<>();

    public DefaultAgentCommandHandler(List<CodingAgentAdapter> adapters, LocalProjectRegistry localProjectRegistry,
                                      CommandDedupCache dedupCache) {
        this.adapters = adapters;
        this.localProjectRegistry = localProjectRegistry;
        this.dedupCache = dedupCache;
    }

    @Override
    public void handle(AgentCommand command, DeviceCredentialState credential, DaemonOutboundSender outboundSender) {
        if (!isIdentityValid(command, credential)) {
            outboundSender.sendCommandAck(rejected(command, "command identity does not match daemon credential"));
            return;
        }
        if (command.commandType() != CommandType.PROMPT || !(command.payload() instanceof PromptCommandPayload payload)) {
            outboundSender.sendCommandAck(rejected(command, "unsupported command type"));
            return;
        }
        if (dedupCache.reserve(command.commandId()) == CommandDedupResult.DUPLICATE) {
            outboundSender.sendCommandAck(new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                    CommandAckStatus.DUPLICATE, CODE_DUPLICATE, "duplicate command", Instant.now(), Map.of()));
            return;
        }
        LocalProject project = localProjectRegistry.findByPlatformProjectId(command.projectId()).orElse(null);
        if (project == null || project.agentType() != command.agentType()) {
            outboundSender.sendCommandAck(rejected(command, "project is not registered locally"));
            return;
        }
        CodingAgentAdapter adapter = adapter(command.agentType());
        if (adapter == null || !adapter.capabilities().prompt()) {
            outboundSender.sendCommandAck(rejected(command, "agent adapter does not support prompt"));
            return;
        }
        CommandAck accepted = new CommandAck(command.commandId(), command.sessionId(), command.deviceId(),
                CommandAckStatus.ACCEPTED, CODE_ACCEPTED, "command accepted", Instant.now(), Map.of());
        if (!outboundSender.sendCommandAck(accepted)) {
            return;
        }
        CodingAgentAdapter activeAdapter = sessionAdapters.computeIfAbsent(command.sessionId(), ignored -> {
            adapter.startSession(new SessionStartRequest(command.sessionId(), command.tenantId(), command.userId(),
                    command.deviceId(), command.projectId(), project.realWorkspace().toString(), command.agentType(),
                    Map.of("commandId", command.commandId())));
            adapter.events(command.sessionId()).subscribe(outboundSender::sendAgentEvent);
            return adapter;
        });
        activeAdapter.sendPrompt(command.sessionId(), new PromptCommand(command.commandId(), payload.prompt(), Map.of()));
        dedupCache.markCompleted(command.commandId());
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
}
