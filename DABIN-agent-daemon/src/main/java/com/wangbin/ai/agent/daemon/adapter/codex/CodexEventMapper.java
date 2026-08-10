package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.event.*;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessageKind;
import com.wangbin.ai.agent.daemon.adapter.codex.protocol.CodexProtocolConstants;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CodexEventMapper {

    private final ObjectMapper objectMapper;

    public CodexEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<AgentEvent> map(CodexRpcMessage message, CodexSessionContext context) {
        if (context == null) {
            return List.of();
        }
        if (message.kind() == CodexRpcMessageKind.SERVER_REQUEST) {
            return List.of(event(context, AgentEventType.PERMISSION_REQUIRED,
                    new PermissionRequiredPayload(message.id(), message.method(), "Codex requested user approval",
                            toMap(message.params()), extensions(message))));
        }
        if (message.kind() != CodexRpcMessageKind.NOTIFICATION) {
            return List.of();
        }
        JsonNode params = message.params();
        return switch (message.method()) {
            case CodexProtocolConstants.METHOD_THREAD_STARTED -> List.of(event(context, AgentEventType.SESSION_STARTED,
                    new SessionPayload(context.nativeSessionId(), AgentSessionStatus.RUNNING, null, extensions(message))));
            case CodexProtocolConstants.METHOD_THREAD_STATUS_CHANGED -> List.of(event(context, AgentEventType.SESSION_STATE_CHANGED,
                    new SessionPayload(context.nativeSessionId(), mapThreadStatus(params), null, extensions(message))));
            case CodexProtocolConstants.METHOD_TURN_STARTED -> List.of(event(context, AgentEventType.SESSION_STATE_CHANGED,
                    new SessionPayload(context.nativeSessionId(), AgentSessionStatus.RUNNING, null, extensions(message))));
            case CodexProtocolConstants.METHOD_TURN_COMPLETED -> List.of(event(context, AgentEventType.SESSION_IDLE,
                    new SessionPayload(context.nativeSessionId(), AgentSessionStatus.IDLE, null, extensions(message))));
            case CodexProtocolConstants.METHOD_AGENT_MESSAGE_DELTA -> List.of(event(context, AgentEventType.AGENT_MESSAGE_DELTA,
                    new AgentMessagePayload(text(params, "itemId"), "assistant", text(params, "delta"), true,
                            extensions(message))));
            case CodexProtocolConstants.METHOD_PLAN_UPDATED -> List.of(event(context, AgentEventType.PLAN_UPDATED,
                    new PlanUpdatedPayload(text(params, "explanation"), planSteps(params), extensions(message))));
            case CodexProtocolConstants.METHOD_COMMAND_OUTPUT_DELTA,
                    CodexProtocolConstants.METHOD_COMMAND_EXEC_OUTPUT_DELTA -> List.of(event(context,
                    AgentEventType.COMMAND_OUTPUT,
                    new CommandOutputPayload(text(params, "itemId", "processId"), text(params, "stream"),
                            text(params, "delta", "deltaBase64"), false, extensions(message))));
            case CodexProtocolConstants.METHOD_FILE_CHANGE_PATCH_UPDATED -> List.of(event(context, AgentEventType.FILE_CHANGED,
                    new FileChangedPayload(null, "patch", "Codex file change patch updated", extensions(message))));
            case CodexProtocolConstants.METHOD_DIFF_UPDATED -> List.of(event(context, AgentEventType.DIFF_UPDATED,
                    new DiffUpdatedPayload(text(params, "diff"), extensions(message))));
            case CodexProtocolConstants.METHOD_ERROR -> List.of(event(context, AgentEventType.ERROR,
                    new AgentErrorPayload("codex_error", params == null ? "Codex error" : params.toString(),
                            params != null && params.path("willRetry").asBoolean(false), extensions(message))));
            case CodexProtocolConstants.METHOD_WARNING, CodexProtocolConstants.METHOD_GUARDIAN_WARNING,
                    CodexProtocolConstants.METHOD_CONFIG_WARNING -> List.of(event(context, AgentEventType.WARNING,
                    new WarningPayload(text(params, "message"), extensions(message))));
            default -> List.of();
        };
    }

    private AgentEvent event(CodexSessionContext context, AgentEventType type, AgentEventPayload payload) {
        return AgentEvent.of(null, context.tenantId(), context.userId(), context.deviceId(), context.projectId(),
                context.platformSessionId(), context.nextSeq(), context.agentType(), type, payload);
    }

    private AgentSessionStatus mapThreadStatus(JsonNode params) {
        String status = text(params.path("status"), "type");
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
        return Map.of("nativeMethod", message.method());
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {
        });
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

}
