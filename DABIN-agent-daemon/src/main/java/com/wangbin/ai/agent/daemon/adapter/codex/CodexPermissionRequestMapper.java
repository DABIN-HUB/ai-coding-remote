package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.enums.PermissionType;
import com.wangbin.ai.agent.contract.event.AgentEventExtensionKeys;
import com.wangbin.ai.agent.contract.permission.CommandExecutionPermissionDetail;
import com.wangbin.ai.agent.contract.permission.FileChangePermissionDetail;
import com.wangbin.ai.agent.contract.permission.FileChangeSummary;
import com.wangbin.ai.agent.contract.permission.PermissionRequestDetail;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import com.wangbin.ai.agent.daemon.adapter.codex.protocol.CodexProtocolConstants;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class CodexPermissionRequestMapper {

    private static final String PERMISSION_PREFIX = "perm_";
    private static final List<PermissionDecision> DEFAULT_AVAILABLE_DECISIONS =
            List.of(PermissionDecision.APPROVED, PermissionDecision.REJECTED);

    private final AgentCodexProperties properties;
    private final CodexPermissionDecisionMapper decisionMapper;

    public CodexPermissionRequestMapper(AgentCodexProperties properties,
                                        CodexPermissionDecisionMapper decisionMapper) {
        this.properties = properties;
        this.decisionMapper = decisionMapper;
    }

    public boolean isSupportedApprovalMethod(String method) {
        return CodexProtocolConstants.METHOD_COMMAND_REQUEST_APPROVAL.equals(method)
                || CodexProtocolConstants.METHOD_FILE_CHANGE_REQUEST_APPROVAL.equals(method);
    }

    public PendingPermission toPendingPermission(CodexRpcMessage message, CodexSessionContext context,
                                                 WorkspaceManager workspaceManager) {
        JsonNode params = message.params();
        PermissionType permissionType = permissionType(message.method());
        List<PermissionDecision> availableDecisions = availableDecisions(params);
        PermissionRequestDetail detail = detail(message.method(), params, context, workspaceManager,
                availableDecisions);
        return new PendingPermission(permissionId(), context.platformSessionId(), context.activePlatformCommandId(),
                text(params, "threadId"), text(params, "turnId"), itemId(params), message.method(),
                context.workspacePath(), message.id(), permissionType, title(permissionType),
                firstNonBlank(text(params, "reason"), ""),
                detail, availableDecisions, Instant.now(), eventExtensions(message));
    }

    public void validateStoredWorkspace(PendingPermission permission, WorkspaceManager workspaceManager) {
        Path workspace = Path.of(permission.workspacePath()).toAbsolutePath().normalize();
        workspaceManager.validateWorkspace(workspace.toString());
        if (permission.detail() instanceof CommandExecutionPermissionDetail detail) {
            validatePath(workspaceManager, workspace, detail.cwd());
        } else if (permission.detail() instanceof FileChangePermissionDetail detail) {
            validatePath(workspaceManager, workspace, detail.grantRoot());
            for (FileChangeSummary change : detail.changes()) {
                validatePath(workspaceManager, workspace, change.path());
            }
        }
    }

    private PermissionRequestDetail detail(String method, JsonNode params, CodexSessionContext context,
                                           WorkspaceManager workspaceManager,
                                           List<PermissionDecision> availableDecisions) {
        Path workspace = workspaceManager.validateWorkspace(context.workspacePath());
        if (CodexProtocolConstants.METHOD_COMMAND_REQUEST_APPROVAL.equals(method)) {
            String cwd = firstNonBlank(text(params, "cwd"), context.workspacePath());
            validatePath(workspaceManager, workspace, cwd);
            return new CommandExecutionPermissionDetail(itemId(params), text(params, "turnId"),
                    truncate(text(params, "command", "cmd")), cwd, firstNonBlank(text(params, "reason"), ""),
                    text(params, "environmentId"), availableDecisions, Map.of());
        }
        String grantRoot = text(params, "grantRoot");
        validatePath(workspaceManager, workspace, grantRoot);
        List<FileChangeSummary> changes = fileChanges(params, workspaceManager, workspace);
        return new FileChangePermissionDetail(itemId(params), text(params, "turnId"),
                firstNonBlank(text(params, "reason"), ""), grantRoot, changes, availableDecisions,
                Map.of());
    }

    private List<FileChangeSummary> fileChanges(JsonNode params, WorkspaceManager workspaceManager, Path workspace) {
        JsonNode changes = changes(params);
        if (changes == null || !changes.isArray()) {
            return List.of();
        }
        List<FileChangeSummary> summaries = new ArrayList<>();
        for (JsonNode change : changes) {
            String path = text(change, "path");
            validatePath(workspaceManager, workspace, path);
            summaries.add(new FileChangeSummary(path, firstNonBlank(text(change, "changeType", "type", "status"),
                    text(change.path("kind"), "type")), false));
        }
        return List.copyOf(summaries);
    }

    private void validatePath(WorkspaceManager workspaceManager, Path workspace, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        workspaceManager.resolveWithinWorkspace(workspace, path);
    }

    private List<PermissionDecision> availableDecisions(JsonNode params) {
        JsonNode decisions = params == null ? null : params.get("availableDecisions");
        if (decisions == null || !decisions.isArray()) {
            return DEFAULT_AVAILABLE_DECISIONS;
        }
        List<PermissionDecision> supported = new ArrayList<>();
        for (JsonNode decision : decisions) {
            PermissionDecision mapped = decisionMapper.fromWireValue(decision.asText());
            if (mapped != null) {
                supported.add(mapped);
            }
        }
        if (supported.isEmpty()) {
            throw new AgentCapabilityException("Codex approval request has no supported decision");
        }
        return List.copyOf(supported);
    }

    private PermissionType permissionType(String method) {
        if (CodexProtocolConstants.METHOD_COMMAND_REQUEST_APPROVAL.equals(method)) {
            return PermissionType.COMMAND_EXECUTION;
        }
        return PermissionType.FILE_CHANGE;
    }

    private String title(PermissionType permissionType) {
        return permissionType == PermissionType.COMMAND_EXECUTION
                ? "Command approval required" : "File change approval required";
    }

    private String itemId(JsonNode params) {
        String itemId = text(params, "itemId");
        if (itemId != null) {
            return itemId;
        }
        return text(item(params), "id");
    }

    private JsonNode changes(JsonNode params) {
        JsonNode direct = params == null ? null : params.get("changes");
        if (direct != null && !direct.isNull()) {
            return direct;
        }
        JsonNode item = item(params);
        return item == null || item.isMissingNode() ? null : item.get("changes");
    }

    private JsonNode item(JsonNode params) {
        return params == null || params.isNull() ? null : params.path("item");
    }

    private Map<String, Object> eventExtensions(CodexRpcMessage message) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put(AgentEventExtensionKeys.NATIVE_METHOD, message.method());
        return Map.copyOf(extensions);
    }

    private String permissionId() {
        return PERMISSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        int max = properties.getPermissionSnapshotMaxChars();
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String text(JsonNode node, String... fields) {
        if (node == null || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child != null && !child.isNull()) {
                return child.isTextual() || child.isNumber() || child.isBoolean()
                        ? child.asText() : truncate(child.toString());
            }
        }
        return null;
    }
}
