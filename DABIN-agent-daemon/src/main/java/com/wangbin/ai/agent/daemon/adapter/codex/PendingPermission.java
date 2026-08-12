package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.enums.PermissionResolutionStatus;
import com.wangbin.ai.agent.contract.enums.PermissionType;
import com.wangbin.ai.agent.contract.permission.PermissionRequestDetail;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class PendingPermission {

    private final String permissionId;
    private final String platformSessionId;
    private final String platformCommandId;
    private final String nativeThreadId;
    private final String nativeTurnId;
    private final String nativeItemId;
    private final String nativeMethod;
    private final String workspacePath;
    private final JsonNode nativeRequestId;
    private final JsonRpcIdKey nativeRequestKey;
    private final PermissionType permissionType;
    private final String title;
    private final String reason;
    private final PermissionRequestDetail detail;
    private final List<PermissionDecision> availableDecisions;
    private final Instant requestedAt;
    private final Map<String, Object> extensions;
    private CodexPermissionState state = CodexPermissionState.PENDING;
    private PermissionDecision decision;
    private String decisionCommandId;
    private Instant resolvedAt;

    public PendingPermission(String permissionId, String platformSessionId, String platformCommandId,
                             String nativeThreadId, String nativeTurnId, String nativeItemId, String nativeMethod,
                             String workspacePath, JsonNode nativeRequestId, PermissionType permissionType,
                             String title, String reason, PermissionRequestDetail detail,
                             List<PermissionDecision> availableDecisions, Instant requestedAt,
                             Map<String, Object> extensions) {
        this.permissionId = permissionId;
        this.platformSessionId = platformSessionId;
        this.platformCommandId = platformCommandId;
        this.nativeThreadId = nativeThreadId;
        this.nativeTurnId = nativeTurnId;
        this.nativeItemId = nativeItemId;
        this.nativeMethod = nativeMethod;
        this.workspacePath = workspacePath;
        this.nativeRequestId = nativeRequestId == null ? null : nativeRequestId.deepCopy();
        this.nativeRequestKey = JsonRpcIdKey.from(nativeRequestId);
        this.permissionType = permissionType;
        this.title = title;
        this.reason = reason;
        this.detail = detail;
        this.availableDecisions = availableDecisions == null ? List.of() : List.copyOf(availableDecisions);
        this.requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        this.extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public String permissionId() {
        return permissionId;
    }

    public String platformSessionId() {
        return platformSessionId;
    }

    public String platformCommandId() {
        return platformCommandId;
    }

    public String nativeThreadId() {
        return nativeThreadId;
    }

    public String nativeTurnId() {
        return nativeTurnId;
    }

    public String nativeItemId() {
        return nativeItemId;
    }

    public String nativeMethod() {
        return nativeMethod;
    }

    public String workspacePath() {
        return workspacePath;
    }

    public JsonNode nativeRequestId() {
        return nativeRequestId == null ? null : nativeRequestId.deepCopy();
    }

    public JsonRpcIdKey nativeRequestKey() {
        return nativeRequestKey;
    }

    public PermissionType permissionType() {
        return permissionType;
    }

    public String title() {
        return title;
    }

    public String reason() {
        return reason;
    }

    public PermissionRequestDetail detail() {
        return detail;
    }

    public List<PermissionDecision> availableDecisions() {
        return availableDecisions;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    public synchronized CodexPermissionDecisionAttempt beginDecision(PermissionDecision requestedDecision,
                                                                     String requestedCommandId) {
        if (!availableDecisions.contains(requestedDecision)) {
            return CodexPermissionDecisionAttempt.UNSUPPORTED_DECISION;
        }
        if (state == CodexPermissionState.DECISION_SENT
                && requestedDecision == decision
                && requestedCommandId != null
                && requestedCommandId.equals(decisionCommandId)) {
            return CodexPermissionDecisionAttempt.DUPLICATE;
        }
        if (state != CodexPermissionState.PENDING) {
            return CodexPermissionDecisionAttempt.NOT_PENDING;
        }
        state = CodexPermissionState.DECISION_SENDING;
        decision = requestedDecision;
        decisionCommandId = requestedCommandId;
        return CodexPermissionDecisionAttempt.RESERVED;
    }

    public synchronized void markDecisionSent(String requestedCommandId) {
        if (state == CodexPermissionState.DECISION_SENDING
                && requestedCommandId != null
                && requestedCommandId.equals(decisionCommandId)) {
            state = CodexPermissionState.DECISION_SENT;
        }
    }

    public synchronized void rollbackDecisionAttempt(String requestedCommandId) {
        if (state == CodexPermissionState.DECISION_SENDING
                && requestedCommandId != null
                && requestedCommandId.equals(decisionCommandId)) {
            state = CodexPermissionState.PENDING;
            decision = null;
            decisionCommandId = null;
        }
    }

    public synchronized boolean markResolved() {
        if (state == CodexPermissionState.RESOLVED) {
            return false;
        }
        state = CodexPermissionState.RESOLVED;
        resolvedAt = Instant.now();
        return true;
    }

    public synchronized PermissionDecision decision() {
        return decision;
    }

    public synchronized String decisionCommandId() {
        return decisionCommandId;
    }

    public synchronized PermissionResolutionStatus resolutionStatus() {
        if (decision == PermissionDecision.APPROVED || decision == PermissionDecision.APPROVED_FOR_SESSION) {
            return PermissionResolutionStatus.APPROVED;
        }
        if (decision == PermissionDecision.REJECTED) {
            return PermissionResolutionStatus.REJECTED;
        }
        if (decision == PermissionDecision.CANCELLED) {
            return PermissionResolutionStatus.CANCELLED;
        }
        return PermissionResolutionStatus.EXPIRED;
    }

    public synchronized Instant resolvedAt() {
        return resolvedAt == null ? Instant.now() : resolvedAt;
    }
}
