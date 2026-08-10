package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.agent.contract.event.CommandOutputPayload;
import com.wangbin.ai.agent.contract.event.FileChangedPayload;
import com.wangbin.ai.agent.contract.event.PermissionRequiredPayload;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.agent.daemon.adapter.codex.protocol.CodexProtocolConstants;
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
        var message = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_AGENT_MESSAGE_DELTA,
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
    void mapsCompletedAgentMessageToSingleFinalMessage() throws Exception {
        var message = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ITEM_COMPLETED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "item": {
                            "id": "msg-1",
                            "type": "agentMessage",
                            "phase": "final_answer",
                            "text": "hello world"
                          }
                        }
                        """));

        var events = mapper.map(message, context);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(AgentEventType.AGENT_MESSAGE);
        AgentMessagePayload payload = (AgentMessagePayload) events.getFirst().payload();
        assertThat(payload.messageId()).isEqualTo("msg-1");
        assertThat(payload.content()).isEqualTo("hello world");
        assertThat(payload.delta()).isFalse();
    }

    @Test
    void ignoresCompletedCommentaryAgentMessageAsFinalMessage() throws Exception {
        var message = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ITEM_COMPLETED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "item": {
                            "id": "msg-commentary",
                            "type": "agentMessage",
                            "phase": "commentary",
                            "text": "working on it"
                          }
                        }
                        """));

        assertThat(mapper.map(message, context)).isEmpty();
    }

    @Test
    void mapsCommandExecutionLifecycleWithoutUsingAgentMessageTypes() throws Exception {
        var started = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ITEM_STARTED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "item": {
                            "id": "cmd-1",
                            "type": "commandExecution",
                            "command": "git status",
                            "status": "inProgress"
                          }
                        }
                        """));
        var completed = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ITEM_COMPLETED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "item": {
                            "id": "cmd-1",
                            "type": "commandExecution",
                            "command": "git status",
                            "commandActions": [],
                            "cwd": "F:/workspace",
                            "status": "completed",
                            "aggregatedOutput": "clean"
                          }
                        }
                        """));

        var startedEvents = mapper.map(started, context);
        var completedEvents = mapper.map(completed, context);

        assertThat(startedEvents.getFirst().type()).isEqualTo(AgentEventType.COMMAND_STARTED);
        assertThat(completedEvents.getFirst().type()).isEqualTo(AgentEventType.COMMAND_COMPLETED);
        CommandOutputPayload payload = (CommandOutputPayload) completedEvents.getFirst().payload();
        assertThat(payload.commandId()).isEqualTo("cmd-1");
        assertThat(payload.output()).isEqualTo("clean");
        assertThat(payload.terminal()).isTrue();
    }

    @Test
    void mapsFileChangePatchAndCompletedFileChangeWithoutAgentMessageTypes() throws Exception {
        var patch = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_FILE_CHANGE_PATCH_UPDATED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "itemId": "file-1",
                          "changes": [
                            {"path": "README.md", "diff": "@@", "kind": {"type": "update"}}
                          ]
                        }
                        """));
        var completed = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ITEM_COMPLETED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "item": {
                            "id": "file-1",
                            "type": "fileChange",
                            "status": "completed",
                            "changes": [
                              {"path": "README.md", "diff": "@@", "kind": {"type": "update"}}
                            ]
                          }
                        }
                        """));

        var patchEvents = mapper.map(patch, context);
        var completedEvents = mapper.map(completed, context);

        assertThat(patchEvents.getFirst().type()).isEqualTo(AgentEventType.FILE_CHANGED);
        assertThat(((FileChangedPayload) patchEvents.getFirst().payload()).path()).isEqualTo("README.md");
        assertThat(completedEvents.getFirst().type()).isEqualTo(AgentEventType.FILE_CHANGED);
        assertThat(((FileChangedPayload) completedEvents.getFirst().payload()).changeType()).isEqualTo("completed");
    }

    @Test
    void ignoresPlanDeltaAndUnknownItemType() throws Exception {
        var planDelta = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_PLAN_DELTA,
                objectMapper.readTree("{\"threadId\":\"native-1\",\"turnId\":\"turn-1\",\"itemId\":\"plan-1\",\"delta\":\"step\"}"));
        var unknownCompleted = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ITEM_COMPLETED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "item": {
                            "id": "unknown-1",
                            "type": "futureItem"
                          }
                        }
                        """));

        assertThat(mapper.map(planDelta, context)).isEmpty();
        assertThat(mapper.map(unknownCompleted, context)).isEmpty();
    }

    @Test
    void mapsTurnCompletedToIdleSessionEvent() throws Exception {
        var message = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_TURN_COMPLETED,
                objectMapper.readTree("{\"threadId\":\"native-1\",\"turn\":{\"id\":\"turn-1\"}}"));

        var events = mapper.map(message, context);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(AgentEventType.SESSION_IDLE);
        SessionPayload payload = (SessionPayload) events.getFirst().payload();
        assertThat(payload.status()).isEqualTo(AgentSessionStatus.IDLE);
    }

    @Test
    void mapsServerApprovalRequestToPermissionRequired() throws Exception {
        var message = CodexRpcMessage.serverRequest("approval-1", CodexProtocolConstants.METHOD_PERMISSION_REQUEST_APPROVAL,
                objectMapper.readTree("{\"threadId\":\"native-1\",\"permission\":\"write\"}"));

        var events = mapper.map(message, context);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(AgentEventType.PERMISSION_REQUIRED);
        PermissionRequiredPayload payload = (PermissionRequiredPayload) events.getFirst().payload();
        assertThat(payload.permissionId()).isEqualTo("approval-1");
        assertThat(payload.title()).isEqualTo(CodexProtocolConstants.METHOD_PERMISSION_REQUEST_APPROVAL);
    }

    @Test
    void ignoresUnknownServerRequestInsteadOfGuessingPermission() throws Exception {
        var message = CodexRpcMessage.serverRequest("request-1", "unknown/serverRequest",
                objectMapper.readTree("{\"threadId\":\"native-1\"}"));

        assertThat(mapper.map(message, context)).isEmpty();
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
        var eventA = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_TURN_STARTED,
                objectMapper.readTree("{\"threadId\":\"native-a\",\"turn\":{\"id\":\"turn-a\"}}"));
        var eventB = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_TURN_STARTED,
                objectMapper.readTree("{\"threadId\":\"native-b\",\"turn\":{\"id\":\"turn-b\"}}"));

        assertThat(mapper.map(eventA, sessionA).getFirst().seq()).isEqualTo(1);
        assertThat(mapper.map(eventA, sessionA).getFirst().seq()).isEqualTo(2);
        assertThat(mapper.map(eventB, sessionB).getFirst().seq()).isEqualTo(1);
        assertThat(mapper.map(eventA, sessionA).getFirst().seq()).isEqualTo(3);
    }

}
