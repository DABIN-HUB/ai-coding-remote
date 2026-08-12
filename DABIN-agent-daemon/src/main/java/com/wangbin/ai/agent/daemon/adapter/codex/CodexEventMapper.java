package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.FileChangeType;
import com.wangbin.ai.agent.contract.enums.SessionControlAction;
import com.wangbin.ai.agent.contract.enums.SessionInterruptInitiator;
import com.wangbin.ai.agent.contract.event.*;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessageKind;
import com.wangbin.ai.agent.daemon.adapter.codex.protocol.CodexProtocolConstants;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CodexEventMapper {

    public List<AgentEvent> map(CodexRpcMessage message, CodexSessionContext context) {
        if (context == null) {
            return List.of();
        }
        if (message.kind() == CodexRpcMessageKind.SERVER_REQUEST) {
            return List.of();
        }
        if (message.kind() != CodexRpcMessageKind.NOTIFICATION) {
            return List.of();
        }
        JsonNode params = message.params();
        return switch (message.method()) {
            case CodexProtocolConstants.METHOD_THREAD_STARTED -> List.of();
            case CodexProtocolConstants.METHOD_THREAD_STATUS_CHANGED -> List.of(event(context, AgentEventType.SESSION_STATE_CHANGED,
                    new SessionPayload(context.nativeSessionId(), mapThreadStatus(params), null, extensions(message))));
            case CodexProtocolConstants.METHOD_TURN_STARTED -> List.of(event(context, AgentEventType.SESSION_STATE_CHANGED,
                    new SessionPayload(context.nativeSessionId(), AgentSessionStatus.RUNNING, null, extensions(message))));
            case CodexProtocolConstants.METHOD_TURN_COMPLETED -> mapTurnCompleted(message, context, params);
            case CodexProtocolConstants.METHOD_ITEM_STARTED -> mapItemStarted(message, context, params);
            case CodexProtocolConstants.METHOD_ITEM_COMPLETED -> mapItemCompleted(message, context, params);
            case CodexProtocolConstants.METHOD_AGENT_MESSAGE_DELTA -> List.of(event(context, AgentEventType.AGENT_MESSAGE_DELTA,
                    new AgentMessagePayload(text(params, "itemId"), "assistant", text(params, "delta"), true,
                            extensions(message))));
            case CodexProtocolConstants.METHOD_PLAN_DELTA -> List.of();
            case CodexProtocolConstants.METHOD_PLAN_UPDATED -> List.of(event(context, AgentEventType.PLAN_UPDATED,
                    new PlanUpdatedPayload(text(params, "explanation"), planSteps(params), extensions(message))));
            case CodexProtocolConstants.METHOD_COMMAND_OUTPUT_DELTA,
                    CodexProtocolConstants.METHOD_COMMAND_EXEC_OUTPUT_DELTA -> List.of(event(context,
                    AgentEventType.COMMAND_OUTPUT,
                    new CommandOutputPayload(text(params, "itemId", "processId"), text(params, "stream"),
                            text(params, "delta", "deltaBase64"), false, extensions(message))));
            case CodexProtocolConstants.METHOD_COMMAND_TERMINAL_INTERACTION,
                    CodexProtocolConstants.METHOD_FILE_CHANGE_OUTPUT_DELTA -> List.of();
            case CodexProtocolConstants.METHOD_FILE_CHANGE_PATCH_UPDATED -> List.of(event(context, AgentEventType.FILE_CHANGED,
                    new FileChangedPayload(firstChangePath(params), null, FileChangeType.UNKNOWN,
                            "Codex file change patch updated", null, null, false, false, false,
                            extensions(message))));
            case CodexProtocolConstants.METHOD_DIFF_UPDATED -> List.of(event(context, AgentEventType.DIFF_UPDATED,
                    new DiffUpdatedPayload(null, text(params, "diff"), null, false, null, null, null,
                            extensions(message))));
            case CodexProtocolConstants.METHOD_ERROR -> List.of(errorEvent(context,
                    CodexErrorExtractor.fromErrorNotification(params), extensions(message)));
            case CodexProtocolConstants.METHOD_WARNING, CodexProtocolConstants.METHOD_GUARDIAN_WARNING,
                    CodexProtocolConstants.METHOD_CONFIG_WARNING -> List.of(event(context, AgentEventType.WARNING,
                    new WarningPayload(firstNonBlank(text(params, "message", "msg"), "Codex warning"),
                            extensions(message))));
            default -> List.of();
        };
    }

    private List<AgentEvent> mapItemStarted(CodexRpcMessage message, CodexSessionContext context, JsonNode params) {
        JsonNode item = item(params);
        String itemType = text(item, "type");
        if (itemType == null) {
            return List.of();
        }
        return switch (itemType) {
            case CodexProtocolConstants.ITEM_TYPE_COMMAND_EXECUTION -> List.of(event(context, AgentEventType.COMMAND_STARTED,
                    new CommandOutputPayload(text(item, "id"), null, text(item, "command"), false,
                            extensions(message, item))));
            case CodexProtocolConstants.ITEM_TYPE_FILE_CHANGE -> List.of(event(context, AgentEventType.TOOL_STARTED,
                    new ToolEventPayload(text(item, "id"), CodexProtocolConstants.ITEM_TYPE_FILE_CHANGE,
                            text(item, "status"), "Codex file change started", extensions(message, item))));
            default -> List.of();
        };
    }

    private List<AgentEvent> mapItemCompleted(CodexRpcMessage message, CodexSessionContext context, JsonNode params) {
        JsonNode item = item(params);
        String itemType = text(item, "type");
        if (itemType == null) {
            return List.of();
        }
        return switch (itemType) {
            case CodexProtocolConstants.ITEM_TYPE_AGENT_MESSAGE -> finalAgentMessage(message, context, item);
            case CodexProtocolConstants.ITEM_TYPE_COMMAND_EXECUTION -> List.of(event(context, AgentEventType.COMMAND_COMPLETED,
                    new CommandOutputPayload(text(item, "id"), null, text(item, "aggregatedOutput"),
                            true, extensions(message, item))));
            case CodexProtocolConstants.ITEM_TYPE_FILE_CHANGE -> List.of(event(context, AgentEventType.FILE_CHANGED,
                    new FileChangedPayload(firstChangePath(item), null, mapFileChangeType(text(item, "status")),
                            "Codex file change completed", null, null, false, false, false,
                            extensions(message, item))));
            default -> List.of();
        };
    }

    private List<AgentEvent> finalAgentMessage(CodexRpcMessage message, CodexSessionContext context, JsonNode item) {
        String messageId = text(item, "id");
        String content = text(item, "text");
        String phase = text(item, "phase");
        if (messageId == null || messageId.isBlank() || content == null
                || CodexProtocolConstants.MESSAGE_PHASE_COMMENTARY.equals(phase)) {
            return List.of();
        }
        return List.of(event(context, AgentEventType.AGENT_MESSAGE,
                new AgentMessagePayload(messageId, "assistant", content, false, extensions(message, item))));
    }

    private List<AgentEvent> mapTurnCompleted(CodexRpcMessage message, CodexSessionContext context, JsonNode params) {
        JsonNode turn = params == null ? null : params.path("turn");
        String status = text(turn, "status");
        Map<String, Object> extensions = extensions(message, turn);
        if (CodexProtocolConstants.TURN_STATUS_COMPLETED.equals(status)) {
            return List.of(event(context, AgentEventType.SESSION_IDLE,
                    new SessionPayload(context.nativeSessionId(), AgentSessionStatus.IDLE, null, extensions)));
        }
        if (CodexProtocolConstants.TURN_STATUS_FAILED.equals(status)) {
            return List.of(errorEvent(context, CodexErrorExtractor.fromFailedTurn(turn), extensions));
        }
        if (CodexProtocolConstants.TURN_STATUS_INTERRUPTED.equals(status)) {
            return List.of(event(context, AgentEventType.SESSION_INTERRUPTED,
                    new SessionInterruptedPayload(context.nativeSessionId(), context.activePlatformCommandId(), null,
                            SessionControlAction.INTERRUPT, SessionInterruptInitiator.SYSTEM,
                            "native turn interrupted", extensions)));
        }
        return List.of(event(context, AgentEventType.SESSION_STATE_CHANGED,
                new SessionPayload(context.nativeSessionId(), AgentSessionStatus.RUNNING, null, extensions)));
    }

    private AgentEvent event(CodexSessionContext context, AgentEventType type, AgentEventPayload payload) {
        Map<String, Object> eventExtensions = new LinkedHashMap<>();
        putIfPresent(eventExtensions, AgentEventExtensionKeys.PLATFORM_COMMAND_ID, context.activePlatformCommandId());
        return new AgentEvent(null, null, context.tenantId(), context.userId(), context.deviceId(), context.projectId(),
                context.platformSessionId(), 0, context.agentType(), type, null, null, payload,
                Map.copyOf(eventExtensions));
    }

    private AgentEvent errorEvent(CodexSessionContext context, CodexErrorClassification error,
                                  Map<String, Object> payloadExtensions) {
        return event(context, AgentEventType.ERROR,
                new AgentErrorPayload(error.code().name(), error.message(), error.retryable(),
                        mergeExtensions(payloadExtensions, error.extensions())));
    }

    private AgentSessionStatus mapThreadStatus(JsonNode params) {
        String status = text(params == null ? null : params.path("status"), "type");
        if ("active".equals(status)) {
            return AgentSessionStatus.RUNNING;
        }
        if ("idle".equals(status)) {
            return AgentSessionStatus.IDLE;
        }
        if ("systemError".equals(status)) {
            return AgentSessionStatus.FAILED;
        }
        return AgentSessionStatus.CREATED;
    }

    private List<PlanStep> planSteps(JsonNode params) {
        JsonNode plan = params == null ? null : params.get("plan");
        if (plan == null || !plan.isArray()) {
            return List.of();
        }
        List<PlanStep> steps = new ArrayList<>();
        for (JsonNode item : plan) {
            steps.add(new PlanStep(text(item, "step", "text"), text(item, "status")));
        }
        return steps;
    }

    private Map<String, Object> extensions(CodexRpcMessage message) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        putIfPresent(extensions, AgentEventExtensionKeys.NATIVE_METHOD, message.method());
        return Map.copyOf(extensions);
    }

    private Map<String, Object> extensions(CodexRpcMessage message, JsonNode item) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        putIfPresent(extensions, AgentEventExtensionKeys.NATIVE_METHOD, message.method());
        putIfPresent(extensions, AgentEventExtensionKeys.NATIVE_ITEM_ID, text(item, "id"));
        putIfPresent(extensions, AgentEventExtensionKeys.NATIVE_ITEM_TYPE, text(item, "type"));
        putIfPresent(extensions, AgentEventExtensionKeys.NATIVE_PHASE, text(item, "phase"));
        putIfPresent(extensions, AgentEventExtensionKeys.NATIVE_STATUS, text(item, "status"));
        return Map.copyOf(extensions);
    }

    private Map<String, Object> mergeExtensions(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        if (first != null) {
            extensions.putAll(first);
        }
        if (second != null) {
            extensions.putAll(second);
        }
        return Map.copyOf(extensions);
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String text(JsonNode node, String... fields) {
        if (node == null || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child != null && !child.isNull()) {
                return child.asText();
            }
        }
        return null;
    }

    private JsonNode item(JsonNode params) {
        return params == null || params.isNull() ? null : params.get("item");
    }

    private String firstChangePath(JsonNode node) {
        JsonNode changes = node == null || node.isNull() ? null : node.get("changes");
        if (changes == null || !changes.isArray() || changes.isEmpty()) {
            return null;
        }
        return text(changes.get(0), "path");
    }

    private FileChangeType mapFileChangeType(String status) {
        if (status == null || status.isBlank()) {
            return FileChangeType.UNKNOWN;
        }
        return switch (status) {
            case "added", "created", "add" -> FileChangeType.ADDED;
            case "modified", "updated", "completed", "patch" -> FileChangeType.MODIFIED;
            case "deleted", "removed", "delete" -> FileChangeType.DELETED;
            case "renamed", "rename" -> FileChangeType.RENAMED;
            default -> FileChangeType.UNKNOWN;
        };
    }

}
