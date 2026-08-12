package com.wangbin.ai.agent.daemon.command;

import com.wangbin.ai.agent.contract.command.*;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.enums.SessionControlAction;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentErrorPayload;
import com.wangbin.ai.agent.contract.event.AgentEventExtensionKeys;
import com.wangbin.ai.agent.contract.event.SessionInterruptedPayload;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.agent.contract.session.*;
import com.wangbin.ai.agent.daemon.adapter.CodingAgentAdapter;
import com.wangbin.ai.agent.daemon.artifact.ArtifactPrepareUploadResponse;
import com.wangbin.ai.agent.daemon.artifact.ArtifactTransferManager;
import com.wangbin.ai.agent.daemon.cloud.controlplane.ControlPlaneClient;
import com.wangbin.ai.agent.daemon.cloud.relay.DaemonOutboundSender;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.event.change.SensitivePathPolicy;
import com.wangbin.ai.agent.daemon.event.change.WorkspaceRelativePathNormalizer;
import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.project.LocalProject;
import com.wangbin.ai.agent.daemon.project.LocalProjectRegistry;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

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
    private static final String TEST_SESSION_ID_SECOND = "ses-2";
    private static final String TEST_COMMAND_ID = "cmd-1";
    private static final String TEST_COMMAND_ID_SECOND = "cmd-2";
    private static final String TEST_PERMISSION_ID = "perm-1";
    private static final String TEST_ARTIFACT_ID = "art-1";
    private static final String TEST_FILE_CHANGE_ID = "fchg-1";
    private static final String TEST_CHANGE_SET_ID = "chg-1";
    private static final String TEST_PROMPT = "inspect project";

    @Test
    void acceptedAckIsEnqueuedBeforeAdapterExecutesPromptAndDuplicateDoesNotExecuteAgain() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());
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
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());
        DeviceCredentialState credential = credential();

        handler.handle(promptCommand("cmd-wrong-device", "another-device"), credential, outboundSender);

        assertThat(adapter.startSessionCalls).hasValue(0);
        assertThat(adapter.sendPromptCalls).hasValue(0);
        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.REJECTED);
    }

    @Test
    void invalidProjectOrWorkspaceRejectsBeforeDedupReserveAndAdapterExecution() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        CountingDedupCache dedupCache = new CountingDedupCache();
        DefaultAgentCommandHandler missingProjectHandler = new DefaultAgentCommandHandler(List.of(adapter),
                emptyRegistry(), dedupCache, workspaceManager());

        missingProjectHandler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);

        assertThat(dedupCache.reserveCalls).hasValue(0);
        assertThat(adapter.startSessionCalls).hasValue(0);
        assertThat(outboundSender.acks).extracting(CommandAck::status).containsExactly(CommandAckStatus.REJECTED);

        RecordingOutboundSender workspaceRejectSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler workspaceRejectHandler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                dedupCache, rejectingWorkspaceManager());

        workspaceRejectHandler.handle(promptCommand(TEST_COMMAND_ID_SECOND), credential(), workspaceRejectSender);

        assertThat(dedupCache.reserveCalls).hasValue(0);
        assertThat(adapter.startSessionCalls).hasValue(0);
        assertThat(workspaceRejectSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.REJECTED);
    }

    @Test
    void rejectedAcceptedAckDoesNotExecutePromptAndReleasesDedup() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter, false);
        CountingDedupCache dedupCache = new CountingDedupCache();
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(), dedupCache,
                workspaceManager());

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);

        assertThat(outboundSender.acks).extracting(CommandAck::status).containsExactly(CommandAckStatus.ACCEPTED);
        assertThat(adapter.startSessionCalls).hasValue(0);
        assertThat(adapter.sendPromptCalls).hasValue(0);
        assertThat(dedupCache.releaseCalls).hasValue(1);
    }

    @Test
    void sendPromptFailureReturnsFailedAckWithoutThrowingWebSocketProtocolError() {
        RecordingAdapter adapter = new RecordingAdapter();
        adapter.failSendPrompt = true;
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);

        assertThat(adapter.startSessionCalls).hasValue(1);
        assertThat(adapter.sendPromptCalls).hasValue(1);
        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.FAILED);
    }

    @Test
    void sameSessionBusyRejectsSecondPromptButDifferentSessionCanRun() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());

        handler.handle(promptCommand(TEST_COMMAND_ID, TEST_SESSION_ID, TEST_DEVICE_ID), credential(), outboundSender);
        handler.handle(promptCommand(TEST_COMMAND_ID_SECOND, TEST_SESSION_ID, TEST_DEVICE_ID), credential(),
                outboundSender);
        handler.handle(promptCommand("cmd-3", TEST_SESSION_ID_SECOND, TEST_DEVICE_ID), credential(), outboundSender);

        assertThat(adapter.startSessionCalls).hasValue(2);
        assertThat(adapter.sendPromptCalls).hasValue(2);
        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.REJECTED, CommandAckStatus.ACCEPTED);
        assertThat(outboundSender.acks.get(1).code()).isEqualTo("SESSION_BUSY");
    }

    @Test
    void importantOutboundFailureDoesNotReleaseActiveCommandUntilSessionIdle() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter, true, false);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);
        adapter.emit(sessionEvent(AgentEventType.SESSION_STARTED, TEST_COMMAND_ID));
        handler.handle(promptCommand(TEST_COMMAND_ID_SECOND), credential(), outboundSender);
        adapter.emit(sessionEvent(AgentEventType.SESSION_IDLE, TEST_COMMAND_ID));
        handler.handle(promptCommand("cmd-3"), credential(), outboundSender);

        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.REJECTED, CommandAckStatus.ACCEPTED);
        assertThat(outboundSender.acks.get(1).code()).isEqualTo("SESSION_BUSY");
        assertThat(adapter.sendPromptCalls).hasValue(2);
    }

    @Test
    void retryableErrorKeepsSessionBusyAndTerminalErrorReleasesIt() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);
        adapter.emit(errorEvent(TEST_COMMAND_ID, true));
        handler.handle(promptCommand(TEST_COMMAND_ID_SECOND), credential(), outboundSender);
        adapter.emit(errorEvent(TEST_COMMAND_ID, false));
        handler.handle(promptCommand("cmd-3"), credential(), outboundSender);

        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.REJECTED, CommandAckStatus.ACCEPTED);
        assertThat(outboundSender.acks.get(1).code()).isEqualTo("SESSION_BUSY");
        assertThat(adapter.sendPromptCalls).hasValue(2);
    }

    @Test
    void sessionCompletedReleasesActiveCommand() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);
        adapter.emit(sessionEvent(AgentEventType.SESSION_COMPLETED, TEST_COMMAND_ID));
        handler.handle(promptCommand(TEST_COMMAND_ID_SECOND), credential(), outboundSender);

        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.ACCEPTED);
        assertThat(adapter.sendPromptCalls).hasValue(2);
    }

    @Test
    void permissionDecisionBypassesActivePromptBusyAndDoesNotReleasePromptCommand() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);
        handler.handle(permissionCommand("cmd-perm-1", PermissionDecision.APPROVED, CommandType.APPROVE_PERMISSION),
                credential(), outboundSender);
        handler.handle(promptCommand(TEST_COMMAND_ID_SECOND), credential(), outboundSender);

        assertThat(adapter.resolvePermissionCalls).hasValue(1);
        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.ACCEPTED, CommandAckStatus.REJECTED);
        assertThat(outboundSender.acks.get(2).code()).isEqualTo("SESSION_BUSY");
    }

    @Test
    void duplicatePermissionDecisionCommandDoesNotResolveNativeRequestTwice() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());
        AgentCommand permission = permissionCommand("cmd-perm-1", PermissionDecision.REJECTED,
                CommandType.REJECT_PERMISSION);

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);
        handler.handle(permission, credential(), outboundSender);
        handler.handle(permission, credential(), outboundSender);

        assertThat(adapter.resolvePermissionCalls).hasValue(1);
        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.ACCEPTED, CommandAckStatus.DUPLICATE);
    }

    @Test
    void permissionDecisionFailureReturnsFailedAck() {
        RecordingAdapter adapter = new RecordingAdapter();
        adapter.failResolvePermission = true;
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);
        handler.handle(permissionCommand("cmd-perm-1", PermissionDecision.CANCELLED, CommandType.REJECT_PERMISSION),
                credential(), outboundSender);

        assertThat(adapter.resolvePermissionCalls).hasValue(1);
        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.FAILED);
    }

    @Test
    void fetchArtifactIsAcceptedAfterQueueAndDoesNotUseCodingAdapter() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        RecordingArtifactTransferManager artifactTransferManager = new RecordingArtifactTransferManager();
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager(),
                artifactTransferManager);

        handler.handle(fetchArtifactCommand(TEST_COMMAND_ID), credential(), outboundSender);

        assertThat(adapter.startSessionCalls).hasValue(0);
        assertThat(adapter.sendPromptCalls).hasValue(0);
        assertThat(artifactTransferManager.canResolveCalls).hasValue(1);
        assertThat(artifactTransferManager.submitCalls).hasValue(1);
        assertThat(artifactTransferManager.pending.startCalls).hasValue(1);
        assertThat(outboundSender.acks).extracting(CommandAck::status).containsExactly(CommandAckStatus.ACCEPTED);
    }

    @Test
    void fetchArtifactActivePromptIsRejectedAsBusyBeforeSubmittingTransfer() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        RecordingArtifactTransferManager artifactTransferManager = new RecordingArtifactTransferManager();
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager(),
                artifactTransferManager);

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);
        handler.handle(fetchArtifactCommand(TEST_COMMAND_ID_SECOND), credential(), outboundSender);

        assertThat(artifactTransferManager.submitCalls).hasValue(0);
        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.REJECTED);
        assertThat(outboundSender.acks.get(1).code()).isEqualTo("SESSION_BUSY");
    }

    @Test
    void fetchArtifactQueueRejectReturnsRejectedAndReleasesDedup() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        CountingDedupCache dedupCache = new CountingDedupCache();
        RecordingArtifactTransferManager artifactTransferManager = new RecordingArtifactTransferManager();
        artifactTransferManager.rejectSubmit = true;
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(), dedupCache,
                workspaceManager(), artifactTransferManager);

        handler.handle(fetchArtifactCommand(TEST_COMMAND_ID), credential(), outboundSender);

        assertThat(dedupCache.releaseCalls).hasValue(1);
        assertThat(outboundSender.acks).extracting(CommandAck::status).containsExactly(CommandAckStatus.REJECTED);
        assertThat(outboundSender.acks.getFirst().code()).isEqualTo("ARTIFACT_TRANSFER_BUSY");
    }

    @Test
    void fetchArtifactAckFailureCancelsQueuedTransferAndDoesNotStartUpload() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter, false);
        CountingDedupCache dedupCache = new CountingDedupCache();
        RecordingArtifactTransferManager artifactTransferManager = new RecordingArtifactTransferManager();
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(), dedupCache,
                workspaceManager(), artifactTransferManager);

        handler.handle(fetchArtifactCommand(TEST_COMMAND_ID), credential(), outboundSender);

        assertThat(dedupCache.releaseCalls).hasValue(1);
        assertThat(artifactTransferManager.pending.cancelCalls).hasValue(1);
        assertThat(artifactTransferManager.pending.startCalls).hasValue(0);
        assertThat(outboundSender.acks).extracting(CommandAck::status).containsExactly(CommandAckStatus.ACCEPTED);
    }

    @Test
    void cancelBypassesPromptBusyCancelsPendingPermissionAndInterruptsNativeTurnOnce() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());
        AgentCommand cancel = cancelCommand("cmd-cancel-1", TEST_COMMAND_ID);

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);
        handler.handle(cancel, credential(), outboundSender);
        handler.handle(cancel, credential(), outboundSender);
        handler.handle(promptCommand(TEST_COMMAND_ID_SECOND), credential(), outboundSender);

        assertThat(adapter.cancelPendingPermissionCalls).hasValue(1);
        assertThat(adapter.interruptCalls).hasValue(1);
        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.ACCEPTED, CommandAckStatus.DUPLICATE,
                        CommandAckStatus.REJECTED);
        assertThat(outboundSender.acks.get(3).code()).isEqualTo("SESSION_BUSY");
    }

    @Test
    void interruptedLifecycleReleasesPromptAndEnrichesCancelIntent() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);
        handler.handle(cancelCommand("cmd-cancel-1", TEST_COMMAND_ID), credential(), outboundSender);
        adapter.emit(sessionInterruptedEvent(TEST_COMMAND_ID));
        handler.handle(promptCommand(TEST_COMMAND_ID_SECOND), credential(), outboundSender);

        AgentEvent interrupted = outboundSender.events.stream()
                .filter(event -> event.type() == AgentEventType.SESSION_INTERRUPTED)
                .findFirst()
                .orElseThrow();
        SessionInterruptedPayload payload = (SessionInterruptedPayload) interrupted.payload();
        assertThat(payload.action()).isEqualTo(SessionControlAction.CANCEL);
        assertThat(payload.targetCommandId()).isEqualTo(TEST_COMMAND_ID);
        assertThat(payload.controlCommandId()).isEqualTo("cmd-cancel-1");
        assertThat(adapter.sendPromptCalls).hasValue(2);
    }

    @Test
    void wrongTargetControlCommandIsRejectedWithoutNativeInterrupt() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);
        handler.handle(cancelCommand("cmd-cancel-1", "cmd-other"), credential(), outboundSender);

        assertThat(adapter.interruptCalls).hasValue(0);
        assertThat(outboundSender.acks).extracting(CommandAck::status)
                .containsExactly(CommandAckStatus.ACCEPTED, CommandAckStatus.REJECTED);
        assertThat(outboundSender.acks.get(1).code()).isEqualTo("TARGET_COMMAND_NOT_ACTIVE");
    }

    @Test
    void closeActiveSessionInterruptsThenClosesAfterInterruptedLifecycle() {
        RecordingAdapter adapter = new RecordingAdapter();
        RecordingOutboundSender outboundSender = new RecordingOutboundSender(adapter);
        DefaultAgentCommandHandler handler = new DefaultAgentCommandHandler(List.of(adapter), registry(),
                new InMemoryCommandDedupCache(new AgentDaemonProperties()), workspaceManager());

        handler.handle(promptCommand(TEST_COMMAND_ID), credential(), outboundSender);
        handler.handle(closeCommand("cmd-close-1", TEST_COMMAND_ID), credential(), outboundSender);
        adapter.emit(sessionInterruptedEvent(TEST_COMMAND_ID));

        assertThat(adapter.interruptCalls).hasValue(1);
        assertThat(adapter.closeSessionCalls).hasValue(1);
        assertThat(adapter.closeSessionControlCommandId).isEqualTo("cmd-close-1");
        SessionInterruptedPayload payload = (SessionInterruptedPayload) outboundSender.events.stream()
                .filter(event -> event.type() == AgentEventType.SESSION_INTERRUPTED)
                .findFirst()
                .orElseThrow()
                .payload();
        assertThat(payload.action()).isEqualTo(SessionControlAction.CLOSE_SESSION);
    }

    private AgentCommand promptCommand(String commandId) {
        return promptCommand(commandId, TEST_SESSION_ID, TEST_DEVICE_ID);
    }

    private AgentCommand promptCommand(String commandId, String deviceId) {
        return promptCommand(commandId, TEST_SESSION_ID, deviceId);
    }

    private AgentCommand promptCommand(String commandId, String sessionId, String deviceId) {
        return new AgentCommand(commandId, commandId, TEST_TENANT_ID, TEST_USER_ID, deviceId,
                TEST_PROJECT_ID, sessionId, AgentType.CODEX, CommandType.PROMPT,
                new PromptCommandPayload(TEST_PROMPT, Map.of()), Instant.now(), Instant.now().plusSeconds(30),
                Map.of());
    }

    private AgentCommand permissionCommand(String commandId, PermissionDecision decision, CommandType commandType) {
        return new AgentCommand(commandId, commandId, TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID,
                TEST_PROJECT_ID, TEST_SESSION_ID, AgentType.CODEX, commandType,
                new PermissionDecisionCommandPayload(TEST_PERMISSION_ID, decision, "reason", Map.of()),
                Instant.now(), Instant.now().plusSeconds(30), Map.of());
    }

    private AgentCommand fetchArtifactCommand(String commandId) {
        return new AgentCommand(commandId, commandId, TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID,
                TEST_PROJECT_ID, TEST_SESSION_ID, AgentType.CODEX, CommandType.FETCH_ARTIFACT,
                new ArtifactFetchCommandPayload(TEST_ARTIFACT_ID, TEST_FILE_CHANGE_ID, TEST_CHANGE_SET_ID,
                        "src/App.java", com.wangbin.ai.agent.contract.enums.ArtifactSourceType.CHANGE_SET_FILE,
                        Map.of()),
                Instant.now(), Instant.now().plusSeconds(30), Map.of());
    }

    private AgentCommand cancelCommand(String commandId, String targetCommandId) {
        return new AgentCommand(commandId, commandId, TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID,
                TEST_PROJECT_ID, TEST_SESSION_ID, AgentType.CODEX, CommandType.CANCEL,
                new CancelCommandPayload(targetCommandId, "cancel", Map.of()),
                Instant.now(), Instant.now().plusSeconds(30), Map.of());
    }

    private AgentCommand closeCommand(String commandId, String targetCommandId) {
        return new AgentCommand(commandId, commandId, TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID,
                TEST_PROJECT_ID, TEST_SESSION_ID, AgentType.CODEX, CommandType.CLOSE_SESSION,
                new CloseSessionCommandPayload(targetCommandId, "close", Map.of()),
                Instant.now(), Instant.now().plusSeconds(30), Map.of());
    }

    private DeviceCredentialState credential() {
        DeviceCredentialState credential = new DeviceCredentialState();
        credential.setTenantId(TEST_TENANT_ID);
        credential.setDeviceId(TEST_DEVICE_ID);
        return credential;
    }

    private AgentEvent sessionEvent(AgentEventType type, String commandId) {
        AgentSessionStatus status = type == AgentEventType.SESSION_IDLE
                ? AgentSessionStatus.IDLE : AgentSessionStatus.RUNNING;
        return new AgentEvent("event-" + type + "-" + commandId, "trace-1", TEST_TENANT_ID, TEST_USER_ID,
                TEST_DEVICE_ID, TEST_PROJECT_ID, TEST_SESSION_ID, 1L, AgentType.CODEX, type,
                EventPriority.IMPORTANT, Instant.now(),
                new SessionPayload("native-1", status, null, Map.of()),
                Map.of(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, commandId));
    }

    private AgentEvent errorEvent(String commandId, boolean retryable) {
        return new AgentEvent("event-error-" + retryable, "trace-1", TEST_TENANT_ID, TEST_USER_ID,
                TEST_DEVICE_ID, TEST_PROJECT_ID, TEST_SESSION_ID, 1L, AgentType.CODEX, AgentEventType.ERROR,
                EventPriority.IMPORTANT, Instant.now(),
                new AgentErrorPayload("codex_error", "error", retryable, Map.of()),
                Map.of(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, commandId));
    }

    private AgentEvent sessionInterruptedEvent(String commandId) {
        return new AgentEvent("event-interrupted-" + commandId, "trace-1", TEST_TENANT_ID, TEST_USER_ID,
                TEST_DEVICE_ID, TEST_PROJECT_ID, TEST_SESSION_ID, 1L, AgentType.CODEX,
                AgentEventType.SESSION_INTERRUPTED, EventPriority.CRITICAL, Instant.now(),
                new SessionInterruptedPayload("native-1", commandId, null, SessionControlAction.INTERRUPT,
                        null, "interrupted", Map.of()),
                Map.of(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, commandId));
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

    private LocalProjectRegistry emptyRegistry() {
        return new LocalProjectRegistry() {
            @Override
            public LocalProject register(String platformProjectId, String localProjectId, String projectName,
                                         String workspacePath, AgentType agentType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<LocalProject> findByPlatformProjectId(String platformProjectId) {
                return Optional.empty();
            }
        };
    }

    private WorkspaceManager workspaceManager() {
        return new WorkspaceManager() {
            @Override
            public Path validateWorkspace(String workspacePath) {
                return Path.of(workspacePath).toAbsolutePath().normalize();
            }

            @Override
            public Path resolveWithinWorkspace(Path workspace, String relativePath) {
                return workspace.resolve(relativePath).normalize();
            }
        };
    }

    private WorkspaceManager rejectingWorkspaceManager() {
        return new WorkspaceManager() {
            @Override
            public Path validateWorkspace(String workspacePath) {
                throw new AgentCapabilityException("workspace rejected");
            }

            @Override
            public Path resolveWithinWorkspace(Path workspace, String relativePath) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final class RecordingOutboundSender implements DaemonOutboundSender {

        private final RecordingAdapter adapter;
        private final List<CommandAck> acks = new ArrayList<>();
        private final List<AgentEvent> events = new ArrayList<>();
        private final boolean acceptCommandAcks;
        private boolean acceptedAckBeforePrompt;

        private RecordingOutboundSender(RecordingAdapter adapter) {
            this(adapter, true);
        }

        private RecordingOutboundSender(RecordingAdapter adapter, boolean acceptCommandAcks) {
            this(adapter, acceptCommandAcks, true);
        }

        private RecordingOutboundSender(RecordingAdapter adapter, boolean acceptCommandAcks, boolean acceptAgentEvents) {
            this.adapter = adapter;
            this.acceptCommandAcks = acceptCommandAcks;
            this.acceptAgentEvents = acceptAgentEvents;
        }

        @Override
        public boolean sendCommandAck(CommandAck ack) {
            acks.add(ack);
            if (ack.status() == CommandAckStatus.ACCEPTED && adapter.sendPromptCalls.get() == 0) {
                acceptedAckBeforePrompt = true;
            }
            return acceptCommandAcks;
        }

        @Override
        public boolean sendAgentEvent(AgentEvent event) {
            events.add(event);
            return acceptAgentEvents;
        }

        private final boolean acceptAgentEvents;
    }

    private static final class RecordingAdapter implements CodingAgentAdapter {

        private final AtomicInteger startSessionCalls = new AtomicInteger();
        private final AtomicInteger sendPromptCalls = new AtomicInteger();
        private final AtomicInteger resolvePermissionCalls = new AtomicInteger();
        private final AtomicInteger interruptCalls = new AtomicInteger();
        private final AtomicInteger cancelPendingPermissionCalls = new AtomicInteger();
        private final AtomicInteger closeSessionCalls = new AtomicInteger();
        private final Sinks.Many<AgentEvent> eventSink = Sinks.many().multicast().directBestEffort();
        private boolean failSendPrompt;
        private boolean failResolvePermission;
        private String closeSessionControlCommandId;

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
            if (failSendPrompt) {
                throw new IllegalStateException("send failed");
            }
        }

        @Override
        public void interrupt(String sessionId) {
            interruptCalls.incrementAndGet();
        }

        @Override
        public void cancelPendingPermissions(String sessionId) {
            cancelPendingPermissionCalls.incrementAndGet();
        }

        @Override
        public void resolvePermission(String sessionId, String permissionId, PermissionDecision decision,
                                      String decisionCommandId) {
            resolvePermissionCalls.incrementAndGet();
            if (failResolvePermission) {
                throw new IllegalStateException("resolve failed");
            }
        }

        @Override
        public Flux<AgentEvent> events(String sessionId) {
            return eventSink.asFlux();
        }

        @Override
        public void closeSession(String sessionId) {
            closeSessionCalls.incrementAndGet();
        }

        @Override
        public void closeSession(String sessionId, String controlCommandId) {
            closeSessionCalls.incrementAndGet();
            closeSessionControlCommandId = controlCommandId;
        }

        private void emit(AgentEvent event) {
            eventSink.tryEmitNext(event);
        }
    }

    private static final class CountingDedupCache implements CommandDedupCache {

        private final AtomicInteger reserveCalls = new AtomicInteger();
        private final AtomicInteger releaseCalls = new AtomicInteger();

        @Override
        public CommandDedupResult reserve(String commandId) {
            reserveCalls.incrementAndGet();
            return CommandDedupResult.RESERVED;
        }

        @Override
        public void markCompleted(String commandId) {
        }

        @Override
        public void release(String commandId) {
            releaseCalls.incrementAndGet();
        }
    }

    private static final class RecordingArtifactTransferManager extends ArtifactTransferManager {

        private final AtomicInteger canResolveCalls = new AtomicInteger();
        private final AtomicInteger submitCalls = new AtomicInteger();
        private RecordingPendingTransfer pending;
        private boolean rejectSubmit;

        private RecordingArtifactTransferManager() {
            super(null, null, null, null, new NoopControlPlaneClient(), new AgentDaemonProperties(),
                    java.util.concurrent.Executors.newSingleThreadExecutor());
        }

        @Override
        public boolean canResolve(AgentCommand command, ArtifactFetchCommandPayload payload) {
            canResolveCalls.incrementAndGet();
            return true;
        }

        @Override
        public PendingArtifactTransfer submit(AgentCommand command, ArtifactFetchCommandPayload payload,
                                              DeviceCredentialState credential) {
            submitCalls.incrementAndGet();
            if (rejectSubmit) {
                return null;
            }
            pending = new RecordingPendingTransfer();
            return pending;
        }
    }

    private static final class RecordingPendingTransfer implements ArtifactTransferManager.PendingArtifactTransfer {

        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public void start() {
            startCalls.incrementAndGet();
        }

        @Override
        public void cancel() {
            cancelCalls.incrementAndGet();
        }
    }

    private static final class NoopControlPlaneClient implements ControlPlaneClient {

        @Override
        public com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceResponse pair(String controlPlaneUrl,
                                                                                      com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.wangbin.ai.agent.daemon.cloud.controlplane.RelayTicketResponse createDeviceRelayTicket(
                DeviceCredentialState credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ArtifactPrepareUploadResponse prepareArtifactUpload(DeviceCredentialState credential,
                                                                   com.wangbin.ai.agent.daemon.artifact.ArtifactPrepareUploadRequest request) {
            return new ArtifactPrepareUploadResponse(true, null, null);
        }
    }
}
