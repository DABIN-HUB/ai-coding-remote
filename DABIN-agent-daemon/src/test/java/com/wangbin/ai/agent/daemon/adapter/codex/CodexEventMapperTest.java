package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.FileChangeType;
import com.wangbin.ai.agent.contract.event.AgentEventExtensionKeys;
import com.wangbin.ai.agent.contract.event.AgentErrorPayload;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.agent.contract.event.CommandOutputPayload;
import com.wangbin.ai.agent.contract.event.FileChangedPayload;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.agent.daemon.adapter.codex.protocol.CodexProtocolConstants;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodexEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CodexEventMapper mapper = new CodexEventMapper();
    private final CodexSessionContext context = new CodexSessionContext("platform-1", "native-1",
            1L, 11L, "device-1", "project-1", "F:/workspace", AgentType.CODEX);
    private static final String TEST_PLATFORM_COMMAND_ID = "cmd-platform-123";

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
        assertThat(events.getFirst().seq()).isZero();
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
        assertThat(payload.extensions())
                .containsEntry(AgentEventExtensionKeys.NATIVE_ITEM_ID, "msg-1")
                .containsEntry(AgentEventExtensionKeys.NATIVE_ITEM_TYPE, "agentMessage")
                .doesNotContainKey("nativeItem");
    }

    @Test
    void mapsActivePlatformCommandIdWithoutReplacingNativeItemId() throws Exception {
        context.beginPlatformCommand(TEST_PLATFORM_COMMAND_ID);
        var message = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ITEM_COMPLETED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "item": {
                            "id": "native-msg-1",
                            "type": "agentMessage",
                            "phase": "final_answer",
                            "text": "hello world"
                          }
                        }
                        """));

        var events = mapper.map(message, context);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().extensions())
                .containsEntry(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, TEST_PLATFORM_COMMAND_ID);
        AgentMessagePayload payload = (AgentMessagePayload) events.getFirst().payload();
        assertThat(payload.messageId()).isEqualTo("native-msg-1");
        assertThat(payload.extensions())
                .containsEntry(AgentEventExtensionKeys.NATIVE_ITEM_ID, "native-msg-1")
                .doesNotContainEntry(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, TEST_PLATFORM_COMMAND_ID);
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
        assertThat(((FileChangedPayload) completedEvents.getFirst().payload()).changeType())
                .isEqualTo(FileChangeType.MODIFIED);
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
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turn": {
                            "id": "turn-1",
                            "status": "completed",
                            "items": []
                          }
                        }
                        """));

        var events = mapper.map(message, context);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(AgentEventType.SESSION_IDLE);
        SessionPayload payload = (SessionPayload) events.getFirst().payload();
        assertThat(payload.status()).isEqualTo(AgentSessionStatus.IDLE);
    }

    @Test
    void mapsNestedRetryableResponseStreamDisconnectedError() throws Exception {
        context.beginPlatformCommand(TEST_PLATFORM_COMMAND_ID);
        var message = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ERROR,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "willRetry": true,
                          "error": {
                            "message": "stream disconnected before completion",
                            "additionalDetails": "response stream disconnected after headers",
                            "codexErrorInfo": {
                              "responseStreamDisconnected": {
                                "httpStatusCode": 502
                              }
                            }
                          }
                        }
                        """));

        var events = mapper.map(message, context);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(AgentEventType.ERROR);
        assertThat(events.getFirst().extensions())
                .containsEntry(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, TEST_PLATFORM_COMMAND_ID);
        AgentErrorPayload payload = (AgentErrorPayload) events.getFirst().payload();
        assertThat(payload.code()).isEqualTo("CODEX_RESPONSE_STREAM_DISCONNECTED");
        assertThat(payload.message()).isEqualTo("stream disconnected before completion");
        assertThat(payload.retryable()).isTrue();
        assertThat(payload.extensions())
                .containsEntry(AgentEventExtensionKeys.NATIVE_METHOD, CodexProtocolConstants.METHOD_ERROR)
                .containsEntry(AgentEventExtensionKeys.NATIVE_ERROR_INFO, "responseStreamDisconnected")
                .containsEntry(AgentEventExtensionKeys.NATIVE_HTTP_STATUS_CODE, 502)
                .containsEntry(AgentEventExtensionKeys.NATIVE_ADDITIONAL_DETAILS,
                        "response stream disconnected after headers")
                .doesNotContainKey("params")
                .doesNotContainKey("rawJson");
    }

    @Test
    void mapsUnauthorizedAndUsageLimitAsTerminalCodexErrors() throws Exception {
        var unauthorized = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ERROR,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "willRetry": false,
                          "error": {
                            "message": "authentication failed",
                            "codexErrorInfo": "unauthorized"
                          }
                        }
                        """));
        var usageLimit = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ERROR,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-2",
                          "willRetry": false,
                          "error": {
                            "message": "usage limit exceeded",
                            "codexErrorInfo": "usageLimitExceeded"
                          }
                        }
                        """));

        AgentErrorPayload unauthorizedPayload = (AgentErrorPayload) mapper.map(unauthorized, context)
                .getFirst().payload();
        AgentErrorPayload usageLimitPayload = (AgentErrorPayload) mapper.map(usageLimit, context)
                .getFirst().payload();

        assertThat(unauthorizedPayload.code()).isEqualTo("CODEX_UNAUTHORIZED");
        assertThat(unauthorizedPayload.retryable()).isFalse();
        assertThat(usageLimitPayload.code()).isEqualTo("CODEX_USAGE_LIMIT_EXCEEDED");
        assertThat(usageLimitPayload.retryable()).isFalse();
    }

    @Test
    void mapsHttpConnectionFailedWithStatusCode() throws Exception {
        var message = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_ERROR,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turnId": "turn-1",
                          "willRetry": true,
                          "error": {
                            "message": "connection failed",
                            "codexErrorInfo": {
                              "httpConnectionFailed": {
                                "httpStatusCode": 503
                              }
                            }
                          }
                        }
                        """));

        AgentErrorPayload payload = (AgentErrorPayload) mapper.map(message, context).getFirst().payload();

        assertThat(payload.code()).isEqualTo("CODEX_HTTP_CONNECTION_FAILED");
        assertThat(payload.retryable()).isTrue();
        assertThat(payload.extensions()).containsEntry(AgentEventExtensionKeys.NATIVE_HTTP_STATUS_CODE, 503);
    }

    @Test
    void mapsFailedTurnToTerminalErrorInsteadOfIdle() throws Exception {
        context.beginPlatformCommand(TEST_PLATFORM_COMMAND_ID);
        var message = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_TURN_COMPLETED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turn": {
                            "id": "turn-1",
                            "status": "failed",
                            "items": [],
                            "error": {
                              "message": "too many failed attempts",
                              "codexErrorInfo": {
                                "responseTooManyFailedAttempts": {
                                  "httpStatusCode": 502
                                }
                              }
                            }
                          }
                        }
                        """));

        var events = mapper.map(message, context);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(AgentEventType.ERROR);
        assertThat(events.getFirst().extensions())
                .containsEntry(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, TEST_PLATFORM_COMMAND_ID);
        AgentErrorPayload payload = (AgentErrorPayload) events.getFirst().payload();
        assertThat(payload.code()).isEqualTo("CODEX_TOO_MANY_FAILED_ATTEMPTS");
        assertThat(payload.message()).isEqualTo("too many failed attempts");
        assertThat(payload.retryable()).isFalse();
        assertThat(payload.extensions())
                .containsEntry(AgentEventExtensionKeys.NATIVE_METHOD, CodexProtocolConstants.METHOD_TURN_COMPLETED)
                .containsEntry(AgentEventExtensionKeys.NATIVE_STATUS, "failed");
    }

    @Test
    void mapsInterruptedTurnToTerminalError() throws Exception {
        var message = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_TURN_COMPLETED,
                objectMapper.readTree("""
                        {
                          "threadId": "native-1",
                          "turn": {
                            "id": "turn-1",
                            "status": "interrupted",
                            "items": []
                          }
                        }
                        """));

        var events = mapper.map(message, context);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(AgentEventType.ERROR);
        AgentErrorPayload payload = (AgentErrorPayload) events.getFirst().payload();
        assertThat(payload.code()).isEqualTo("CODEX_TURN_INTERRUPTED");
        assertThat(payload.retryable()).isFalse();
    }

    @Test
    void ignoresServerApprovalRequestBecauseAdapterOwnsNativeRequestIds() throws Exception {
        var message = CodexRpcMessage.serverRequest("approval-1", CodexProtocolConstants.METHOD_PERMISSION_REQUEST_APPROVAL,
                objectMapper.readTree("{\"threadId\":\"native-1\",\"permission\":\"write\",\"command\":\"rm -rf secret\"}"));

        assertThat(mapper.map(message, context)).isEmpty();
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
    void mapperDoesNotConsumeSequenceForInternalEvents() throws Exception {
        var event = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_TURN_STARTED,
                objectMapper.readTree("{\"threadId\":\"native-1\",\"turn\":{\"id\":\"turn-1\"}}"));

        assertThat(mapper.map(event, context).getFirst().seq()).isZero();
        assertThat(mapper.map(event, context).getFirst().seq()).isZero();
    }

    @Test
    void ignoresThreadStartedNotificationToAvoidDuplicateSessionStarted() throws Exception {
        var message = CodexRpcMessage.notification(CodexProtocolConstants.METHOD_THREAD_STARTED,
                objectMapper.readTree("{\"threadId\":\"native-1\"}"));

        assertThat(mapper.map(message, context)).isEmpty();
    }

}
