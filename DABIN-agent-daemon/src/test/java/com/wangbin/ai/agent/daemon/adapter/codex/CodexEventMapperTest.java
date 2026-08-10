package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.agent.contract.event.PermissionRequiredPayload;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodexEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CodexEventMapper mapper = new CodexEventMapper(objectMapper);
    private final CodexSessionContext context = new CodexSessionContext("platform-1", "native-1",
            1L, 11L, "device-1", "project-1", "F:/workspace", AgentType.CODEX);

    @Test
    void mapsAgentMessageDelta() throws Exception {
        var message = CodexRpcMessage.notification("item/agentMessage/delta",
                objectMapper.readTree("{\"threadId\":\"native-1\",\"itemId\":\"msg-1\",\"delta\":\"hello\"}"));

        var events = mapper.map(message, context);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(AgentEventType.AGENT_MESSAGE_DELTA);
        AgentMessagePayload payload = (AgentMessagePayload) events.getFirst().payload();
        assertThat(payload.messageId()).isEqualTo("msg-1");
        assertThat(payload.content()).isEqualTo("hello");
        assertThat(payload.delta()).isTrue();
    }

    @Test
    void mapsTurnCompletedToIdleSessionEvent() throws Exception {
        var message = CodexRpcMessage.notification("turn/completed",
                objectMapper.readTree("{\"threadId\":\"native-1\",\"turn\":{\"id\":\"turn-1\"}}"));

        var events = mapper.map(message, context);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(AgentEventType.SESSION_IDLE);
        SessionPayload payload = (SessionPayload) events.getFirst().payload();
        assertThat(payload.status()).isEqualTo(AgentSessionStatus.IDLE);
    }

    @Test
    void mapsServerApprovalRequestToPermissionRequired() throws Exception {
        var message = CodexRpcMessage.serverRequest("approval-1", "item/permissions/requestApproval",
                objectMapper.readTree("{\"threadId\":\"native-1\",\"permission\":\"write\"}"));

        var events = mapper.map(message, context);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(AgentEventType.PERMISSION_REQUIRED);
        PermissionRequiredPayload payload = (PermissionRequiredPayload) events.getFirst().payload();
        assertThat(payload.permissionId()).isEqualTo("approval-1");
        assertThat(payload.title()).isEqualTo("item/permissions/requestApproval");
    }

    @Test
    void ignoresUnknownNotifications() throws Exception {
        var message = CodexRpcMessage.notification("unknown/event",
                objectMapper.readTree("{\"threadId\":\"native-1\"}"));

        assertThat(mapper.map(message, context)).isEmpty();
    }

    @Test
    void assignsSequencePerPlatformSession() throws Exception {
        CodexSessionContext sessionA = new CodexSessionContext("platform-a", "native-a",
                1L, 11L, "device-a", "project-a", "F:/workspace-a", AgentType.CODEX);
        CodexSessionContext sessionB = new CodexSessionContext("platform-b", "native-b",
                1L, 12L, "device-b", "project-b", "F:/workspace-b", AgentType.CODEX);
        var eventA = CodexRpcMessage.notification("turn/started",
                objectMapper.readTree("{\"threadId\":\"native-a\",\"turn\":{\"id\":\"turn-a\"}}"));
        var eventB = CodexRpcMessage.notification("turn/started",
                objectMapper.readTree("{\"threadId\":\"native-b\",\"turn\":{\"id\":\"turn-b\"}}"));

        assertThat(mapper.map(eventA, sessionA).getFirst().seq()).isEqualTo(1);
        assertThat(mapper.map(eventA, sessionA).getFirst().seq()).isEqualTo(2);
        assertThat(mapper.map(eventB, sessionB).getFirst().seq()).isEqualTo(1);
        assertThat(mapper.map(eventA, sessionA).getFirst().seq()).isEqualTo(3);
    }

}
