package com.wangbin.ai.agent.relay.websocket;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.command.AgentCommand;
import com.wangbin.ai.agent.contract.command.PromptCommandPayload;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
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

    private <T> WsEnvelope<T> decode(WsEnvelope<T> envelope, Class<T> payloadType) throws Exception {
        String json = objectMapper.writeValueAsString(envelope);
        JavaType type = objectMapper.getTypeFactory().constructParametricType(WsEnvelope.class, payloadType);
        return objectMapper.readValue(json, type);
    }
}
