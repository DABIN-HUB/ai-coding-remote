package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
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
            if (!CodexProtocolConstants.APPROVAL_REQUEST_METHODS.contains(message.method())) {
                return List.of();
            }
            return List.of(event(context, AgentEventType.PERMISSION_REQUIRED,
                    new PermissionRequiredPayload(message.idText(), message.method(), "Codex requested user approval",
                            safePermissionRequest(message.params()), extensions(message))));
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
            case CodexProtocolConstants.METHOD_TURN_COMPLETED -> List.of(event(context, AgentEventType.SESSION_IDLE,
                    new SessionPayload(context.nativeSessionId(), AgentSessionStatus.IDLE, null, extensions(message))));
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
                    new FileChangedPayload(firstChangePath(params), "patch", "Codex file change patch updated",
                            extensions(message))));
            case CodexProtocolConstants.METHOD_DIFF_UPDATED -> List.of(event(context, AgentEventType.DIFF_UPDATED,
                    new DiffUpdatedPayload(text(params, "diff"), extensions(message))));
            case CodexProtocolConstants.METHOD_ERROR -> List.of(event(context, AgentEventType.ERROR,
                    new AgentErrorPayload(errorCode(params), errorMessage(params),
                            params != null && params.path("willRetry").asBoolean(false), extensions(message))));
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
                    new FileChangedPayload(firstChangePath(item), text(item, "status"),
                            "Codex file change completed", extensions(message, item))));
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

    private AgentEvent event(CodexSessionContext context, AgentEventType type, AgentEventPayload payload) {
        return AgentEvent.of(null, context.tenantId(), context.userId(), context.deviceId(), context.projectId(),
                context.platformSessionId(), 0, context.agentType(), type, payload);
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
        putIfPresent(extensions, "nativeMethod", message.method());
        return Map.copyOf(extensions);
    }

    private Map<String, Object> extensions(CodexRpcMessage message, JsonNode item) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        putIfPresent(extensions, "nativeMethod", message.method());
        putIfPresent(extensions, "nativeItemId", text(item, "id"));
        putIfPresent(extensions, "nativeItemType", text(item, "type"));
        putIfPresent(extensions, "nativePhase", text(item, "phase"));
        putIfPresent(extensions, "nativeStatus", text(item, "status"));
        return Map.copyOf(extensions);
    }

    private Map<String, Object> safePermissionRequest(JsonNode params) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "threadId", text(params, "threadId"));
        putIfPresent(request, "turnId", text(params, "turnId"));
        putIfPresent(request, "itemId", text(params, "itemId"));
        putIfPresent(request, "permission", text(params, "permission"));
        putIfPresent(request, "reason", text(params, "reason"));
        putIfPresent(request, "title", text(params, "title"));
        putIfPresent(request, "action", text(params, "action"));
        putIfPresent(request, "status", text(params, "status"));
        return Map.copyOf(request);
    }

    private String errorCode(JsonNode params) {
        return firstNonBlank(text(params, "code", "type"), "codex_error");
    }

    private String errorMessage(JsonNode params) {
        return firstNonBlank(text(params, "message", "msg", "additionalDetails"), "Codex error");
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

}
