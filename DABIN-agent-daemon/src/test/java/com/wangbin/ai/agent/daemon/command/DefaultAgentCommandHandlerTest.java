package com.wangbin.ai.agent.daemon.command;

import com.wangbin.ai.agent.contract.command.*;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.session.*;
import com.wangbin.ai.agent.daemon.adapter.CodingAgentAdapter;
import com.wangbin.ai.agent.daemon.cloud.relay.DaemonOutboundSender;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.project.LocalProject;
import com.wangbin.ai.agent.daemon.project.LocalProjectRegistry;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgentCommandHandlerTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_LOCAL_PROJECT_ID = "local-1";
    private static final String TEST_PROJECT_NAME = "project";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_COMMAND_ID = "cmd-1";
    private static final String TEST_PROMPT = "inspect project";

    @Test
    void acceptedAckIsEnqueuedBeforeAdapterExecutesPromptAndDuplicateDoesNotExecuteAgain() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()));
        DeviceCredentialState credential = credential();
        AgentCommand command = promptCommand(TEST_COMMAND_ID);

        handler.handle(command, credential, outboundSender);
        handler.handle(command, credential, outboundSender);

        assertThat(adapter.startSessionCalls).hasValue(1);
        assertThat(adapter.sendPromptCalls).hasValue(1);
        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.DUPLICATE);
        assertThat(outboundSender.acceptedAckBeforePrompt).isTrue();
    }

    @Test
    void identityMismatchRejectsCommandBeforeAdapterExecution() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()));
        DeviceCredentialState credential = credential();

        handler.handle(promptCommand("cmd-wrong-device", "another-device"), credential, outboundSender);

        assertThat(adapter.startSessionCalls).hasValue(0);
        assertThat(adapter.sendPromptCalls).hasValue(0);
        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.REJECTED);
    }

    private AgentCommand promptCommand(String commandId) {
        return promptCommand(commandId, TEST_DEVICE_ID);
    }

    private AgentCommand promptCommand(String commandId, String deviceId) {
        return new AgentCommand(commandId, commandId, TEST_TENANT_ID, TEST_USER_ID, deviceId,
                TEST_PROJECT_ID, TEST_SESSION_ID, AgentType.CODEX, CommandType.PROMPT,
                new PromptCommandPayload(TEST_PROMPT, Map.of()), Instant.now(), Instant.now().plusSeconds(30),
                Map.of());
    }

    private DeviceCredentialState credential() {
        DeviceCredentialState credential = new DeviceCredentialState();
        credential.setTenantId(TEST_TENANT_ID);
        credential.setDeviceId(TEST_DEVICE_ID);
        return credential;
    }

    private LocalProjectRegistry registry() {
        LocalProject localProject = new LocalProject(TEST_PROJECT_ID, TEST_LOCAL_PROJECT_ID, TEST_PROJECT_NAME,
                Path.of(".").toAbsolutePath().normalize(), AgentType.CODEX);
        return new LocalProjectRegistry() {
            @Override
            public LocalProject register(String platformProjectId, String localProjectId, String projectName,
                                         String workspacePath, AgentType agentType) {
                return localProject;
            }

            @Override
            public Optional<LocalProject> findByPlatformProjectId(String platformProjectId) {
                return TEST_PROJECT_ID.equals(platformProjectId) ? Optional.of(localProject) : Optional.empty();
            }
        };
    }

    private static final class RecordingOutboundSender implements DaemonOutboundSender {

        private final RecordingAdapter adapter;
        private final List<CommandAck> acks = new ArrayList<>();
        private boolean acceptedAckBeforePrompt;

        private RecordingOutboundSender(RecordingAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public boolean sendCommandAck(CommandAck ack) {
            acks.add(ack);
            if (ack.status() == CommandAckStatus.ACCEPTED && adapter.sendPromptCalls.get() == 0) {
                acceptedAckBeforePrompt = true;
            }
            return true;
        }

        @Override
        public boolean sendAgentEvent(AgentEvent event) {
            return true;
        }
    }

    private static final class RecordingAdapter implements CodingAgentAdapter {

        private final AtomicInteger startSessionCalls = new AtomicInteger();
        private final AtomicInteger sendPromptCalls = new AtomicInteger();

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
            startSessionCalls.incrementAndGet();
            return new AgentSession(request.platformSessionId(), "native-1", request.tenantId(), request.userId(),
                    request.deviceId(), request.projectId(), request.agentType(), AgentSessionStatus.RUNNING,
                    capabilities(), Instant.now(), Map.of());
        }

        @Override
        public void sendPrompt(String sessionId, PromptCommand command) {
            sendPromptCalls.incrementAndGet();
        }

        @Override
        public void interrupt(String sessionId) {
        }

        @Override
        public void resolvePermission(String sessionId, String permissionId, PermissionDecision decision) {
        }

        @Override
        public Flux<AgentEvent> events(String sessionId) {
            return Flux.empty();
        }

        @Override
        public void closeSession(String sessionId) {
        }
    }
}
