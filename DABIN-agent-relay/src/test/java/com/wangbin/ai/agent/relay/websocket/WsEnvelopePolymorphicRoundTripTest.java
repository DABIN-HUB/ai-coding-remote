package com.wangbin.ai.agent.relay.websocket;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.command.AgentCommand;
import com.wangbin.ai.agent.contract.command.ArtifactFetchCommandPayload;
import com.wangbin.ai.agent.contract.command.CancelCommandPayload;
import com.wangbin.ai.agent.contract.command.CloseSessionCommandPayload;
import com.wangbin.ai.agent.contract.command.InterruptCommandPayload;
import com.wangbin.ai.agent.contract.command.PromptCommandPayload;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.ArtifactSourceType;
import com.wangbin.ai.agent.contract.enums.ChangeSetStatus;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.contract.enums.FileChangeType;
import com.wangbin.ai.agent.contract.enums.SessionControlAction;
import com.wangbin.ai.agent.contract.enums.SessionInterruptInitiator;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.agent.contract.event.ChangeSetFinalizedPayload;
import com.wangbin.ai.agent.contract.event.ChangedFileSummary;
import com.wangbin.ai.agent.contract.event.DiffUpdatedPayload;
import com.wangbin.ai.agent.contract.event.FileChangedPayload;
import com.wangbin.ai.agent.contract.event.SessionInterruptedPayload;
import com.wangbin.ai.agent.contract.websocket.WsEnvelope;
import com.wangbin.ai.agent.contract.websocket.WsMessageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WsEnvelopePolymorphicRoundTripTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_COMMAND_ID = "cmd-123";

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void agentCommandPromptPayloadRoundTripsInsideWsEnvelope() throws Exception {
        AgentCommand command = new AgentCommand(TEST_COMMAND_ID, "trace-1", TEST_TENANT_ID, TEST_USER_ID,
                TEST_DEVICE_ID, TEST_PROJECT_ID, TEST_SESSION_ID, AgentType.CODEX, CommandType.PROMPT,
                new PromptCommandPayload("hello", Map.of()), Instant.now(), null, Map.of());

        WsEnvelope<AgentCommand> decoded = decode(WsEnvelope.of(WsMessageType.AGENT_COMMAND, command),
                AgentCommand.class);

        assertThat(decoded.type()).isEqualTo(WsMessageType.AGENT_COMMAND);
        assertThat(decoded.payload().commandId()).isEqualTo(TEST_COMMAND_ID);
        assertThat(decoded.payload().payload()).isInstanceOf(PromptCommandPayload.class);
        assertThat(((PromptCommandPayload) decoded.payload().payload()).prompt()).isEqualTo("hello");
    }

    @Test
    void agentCommandArtifactFetchPayloadRoundTripsInsideWsEnvelope() throws Exception {
        AgentCommand command = new AgentCommand(TEST_COMMAND_ID, "trace-1", TEST_TENANT_ID, TEST_USER_ID,
                TEST_DEVICE_ID, TEST_PROJECT_ID, TEST_SESSION_ID, AgentType.CODEX, CommandType.FETCH_ARTIFACT,
                new ArtifactFetchCommandPayload("art-1", "fchg-1", "chg-1", "src/App.java",
                        ArtifactSourceType.CHANGE_SET_FILE, Map.of()),
                Instant.now(), null, Map.of());

        WsEnvelope<AgentCommand> decoded = decode(WsEnvelope.of(WsMessageType.AGENT_COMMAND, command),
                AgentCommand.class);

        assertThat(decoded.type()).isEqualTo(WsMessageType.AGENT_COMMAND);
        assertThat(decoded.payload().commandType()).isEqualTo(CommandType.FETCH_ARTIFACT);
        assertThat(decoded.payload().payload()).isInstanceOf(ArtifactFetchCommandPayload.class);
        ArtifactFetchCommandPayload payload = (ArtifactFetchCommandPayload) decoded.payload().payload();
        assertThat(payload.artifactId()).isEqualTo("art-1");
        assertThat(payload.relativePath()).isEqualTo("src/App.java");
        assertThat(payload.sourceType()).isEqualTo(ArtifactSourceType.CHANGE_SET_FILE);
    }

    @Test
    void sessionControlCommandPayloadsRoundTripInsideWsEnvelope() throws Exception {
        AgentCommand interrupt = controlCommand(CommandType.INTERRUPT,
                new InterruptCommandPayload(TEST_COMMAND_ID, "pause", Map.of()));
        AgentCommand cancel = controlCommand(CommandType.CANCEL,
                new CancelCommandPayload(TEST_COMMAND_ID, "cancel", Map.of()));
        AgentCommand close = controlCommand(CommandType.CLOSE_SESSION,
                new CloseSessionCommandPayload(TEST_COMMAND_ID, "close", Map.of()));

        assertThat(decode(WsEnvelope.of(WsMessageType.AGENT_COMMAND, interrupt), AgentCommand.class)
                .payload().payload()).isInstanceOf(InterruptCommandPayload.class);
        assertThat(decode(WsEnvelope.of(WsMessageType.AGENT_COMMAND, cancel), AgentCommand.class)
                .payload().payload()).isInstanceOf(CancelCommandPayload.class);
        assertThat(decode(WsEnvelope.of(WsMessageType.AGENT_COMMAND, close), AgentCommand.class)
                .payload().payload()).isInstanceOf(CloseSessionCommandPayload.class);
    }

    @Test
    void agentEventMessagePayloadRoundTripsInsideWsEnvelope() throws Exception {
        AgentEvent event = AgentEvent.of("trace-1", TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID, TEST_PROJECT_ID,
                TEST_SESSION_ID, 1L, AgentType.CODEX, AgentEventType.AGENT_MESSAGE,
                new AgentMessagePayload("msg-1", "assistant", "answer", false, Map.of()));

        WsEnvelope<AgentEvent> decoded = decode(WsEnvelope.of(WsMessageType.AGENT_EVENT, event), AgentEvent.class);

        assertThat(decoded.type()).isEqualTo(WsMessageType.AGENT_EVENT);
        assertThat(decoded.payload().type()).isEqualTo(AgentEventType.AGENT_MESSAGE);
        assertThat(decoded.payload().payload()).isInstanceOf(AgentMessagePayload.class);
        assertThat(((AgentMessagePayload) decoded.payload().payload()).content()).isEqualTo("answer");
    }

    @Test
    void changeReviewPayloadsRoundTripInsideWsEnvelope() throws Exception {
        AgentEvent fileChanged = AgentEvent.of("trace-1", TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID,
                TEST_PROJECT_ID, TEST_SESSION_ID, 1L, AgentType.CODEX, AgentEventType.FILE_CHANGED,
                new FileChangedPayload("src/App.java", null, FileChangeType.MODIFIED, "updated",
                        2, 1, false, false, false, Map.of()));
        AgentEvent diffUpdated = AgentEvent.of("trace-1", TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID,
                TEST_PROJECT_ID, TEST_SESSION_ID, 2L, AgentType.CODEX, AgentEventType.DIFF_UPDATED,
                new DiffUpdatedPayload("chg-1", "@@", "sha", false, 1, 2, 1, Map.of()));
        AgentEvent finalized = AgentEvent.of("trace-1", TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID,
                TEST_PROJECT_ID, TEST_SESSION_ID, 3L, AgentType.CODEX, AgentEventType.CHANGE_SET_FINALIZED,
                new ChangeSetFinalizedPayload("chg-1", ChangeSetStatus.COMPLETED, 1, 2, 1, "@@", "sha",
                        false, false,
                        java.util.List.of(new ChangedFileSummary("src/App.java", null,
                                FileChangeType.MODIFIED, 2, 1, false, false, false, "@@", "patch-sha")),
                        Instant.now(), Map.of()));

        assertThat(decode(WsEnvelope.of(WsMessageType.AGENT_EVENT, fileChanged), AgentEvent.class).payload()
                .payload()).isInstanceOf(FileChangedPayload.class);
        assertThat(decode(WsEnvelope.of(WsMessageType.AGENT_EVENT, diffUpdated), AgentEvent.class).payload()
                .payload()).isInstanceOf(DiffUpdatedPayload.class);
        ChangeSetFinalizedPayload decoded = (ChangeSetFinalizedPayload) decode(
                WsEnvelope.of(WsMessageType.AGENT_EVENT, finalized), AgentEvent.class).payload().payload();
        assertThat(decoded.files().getFirst().changeType()).isEqualTo(FileChangeType.MODIFIED);
        assertThat(decoded.status()).isEqualTo(ChangeSetStatus.COMPLETED);
    }

    @Test
    void sessionInterruptedPayloadRoundTripsInsideWsEnvelope() throws Exception {
        AgentEvent event = AgentEvent.of("trace-1", TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID, TEST_PROJECT_ID,
                TEST_SESSION_ID, 4L, AgentType.CODEX, AgentEventType.SESSION_INTERRUPTED,
                new SessionInterruptedPayload("native-1", TEST_COMMAND_ID, "cmd-cancel-1",
                        SessionControlAction.CANCEL, SessionInterruptInitiator.USER, "cancel", Map.of()));

        WsEnvelope<AgentEvent> decoded = decode(WsEnvelope.of(WsMessageType.AGENT_EVENT, event), AgentEvent.class);

        assertThat(decoded.payload().type()).isEqualTo(AgentEventType.SESSION_INTERRUPTED);
        SessionInterruptedPayload payload = (SessionInterruptedPayload) decoded.payload().payload();
        assertThat(payload.action()).isEqualTo(SessionControlAction.CANCEL);
        assertThat(payload.targetCommandId()).isEqualTo(TEST_COMMAND_ID);
        assertThat(payload.controlCommandId()).isEqualTo("cmd-cancel-1");
    }

    private <T> WsEnvelope<T> decode(WsEnvelope<T> envelope, Class<T> payloadType) throws Exception {
        String json = objectMapper.writeValueAsString(envelope);
        JavaType type = objectMapper.getTypeFactory().constructParametricType(WsEnvelope.class, payloadType);
        return objectMapper.readValue(json, type);
    }

    private AgentCommand controlCommand(CommandType type, com.wangbin.ai.agent.contract.command.AgentCommandPayload payload) {
        return new AgentCommand("cmd-control-" + type.name(), "trace-1", TEST_TENANT_ID, TEST_USER_ID,
                TEST_DEVICE_ID, TEST_PROJECT_ID, TEST_SESSION_ID, AgentType.CODEX, type, payload,
                Instant.now(), null, Map.of());
    }
}
