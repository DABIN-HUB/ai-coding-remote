package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.ai.agent.contract.event.AgentEventExtensionKeys;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts Codex 0.147 app-server TurnError structures into platform-safe
 * summaries. Native raw JSON is deliberately not copied into extensions.
 */
final class CodexErrorExtractor {

    private static final int SUMMARY_MAX_LENGTH = 512;
    private static final String FALLBACK_MESSAGE = "Codex error";

    private CodexErrorExtractor() {
    }

    static CodexErrorClassification fromErrorNotification(JsonNode params) {
        JsonNode error = params == null ? null : params.path("error");
        boolean retryable = params != null && params.path("willRetry").asBoolean(false);
        return classify(error, retryable, CodexPlatformErrorCode.CODEX_OTHER);
    }

    static CodexErrorClassification fromFailedTurn(JsonNode turn) {
        JsonNode error = turn == null ? null : turn.path("error");
        return classify(error, false, CodexPlatformErrorCode.CODEX_TURN_FAILED);
    }

    static CodexErrorClassification interruptedTurn() {
        return new CodexErrorClassification(CodexPlatformErrorCode.CODEX_TURN_INTERRUPTED,
                "Codex turn interrupted", false, Map.of());
    }

    private static CodexErrorClassification classify(JsonNode error, boolean retryable,
                                                     CodexPlatformErrorCode fallbackCode) {
        String message = firstNonBlank(text(error, "message"), FALLBACK_MESSAGE);
        String additionalDetails = text(error, "additionalDetails");
        JsonNode errorInfo = error == null ? null : error.path("codexErrorInfo");
        NativeErrorInfo nativeErrorInfo = nativeErrorInfo(errorInfo);
        CodexPlatformErrorCode code = nativeErrorInfo.code() == null ? fallbackCode : nativeErrorInfo.code();
        Map<String, Object> extensions = new LinkedHashMap<>();
        putIfPresent(extensions, AgentEventExtensionKeys.NATIVE_ERROR_INFO, nativeErrorInfo.name());
        if (nativeErrorInfo.httpStatusCode() != null) {
            extensions.put(AgentEventExtensionKeys.NATIVE_HTTP_STATUS_CODE, nativeErrorInfo.httpStatusCode());
        }
        putIfPresent(extensions, AgentEventExtensionKeys.NATIVE_ADDITIONAL_DETAILS, sanitize(additionalDetails));
        return new CodexErrorClassification(code, sanitize(message), retryable, extensions);
    }

    private static NativeErrorInfo nativeErrorInfo(JsonNode errorInfo) {
        if (errorInfo == null || errorInfo.isMissingNode() || errorInfo.isNull()) {
            return new NativeErrorInfo(null, null, null);
        }
        if (errorInfo.isTextual()) {
            String name = errorInfo.asText();
            return new NativeErrorInfo(name, codeFor(name), null);
        }
        if (!errorInfo.isObject()) {
            return new NativeErrorInfo(errorInfo.asText(), CodexPlatformErrorCode.CODEX_OTHER, null);
        }
        Iterator<String> fields = errorInfo.fieldNames();
        if (!fields.hasNext()) {
            return new NativeErrorInfo(null, null, null);
        }
        String name = fields.next();
        JsonNode detail = errorInfo.path(name);
        Integer httpStatusCode = detail.path("httpStatusCode").isNumber()
                ? detail.path("httpStatusCode").asInt() : null;
        return new NativeErrorInfo(name, codeFor(name), httpStatusCode);
    }

    private static CodexPlatformErrorCode codeFor(String nativeName) {
        if (nativeName == null || nativeName.isBlank()) {
            return CodexPlatformErrorCode.CODEX_OTHER;
        }
        return switch (nativeName) {
            case "contextWindowExceeded" -> CodexPlatformErrorCode.CODEX_CONTEXT_WINDOW_EXCEEDED;
            case "sessionBudgetExceeded" -> CodexPlatformErrorCode.CODEX_SESSION_BUDGET_EXCEEDED;
            case "usageLimitExceeded" -> CodexPlatformErrorCode.CODEX_USAGE_LIMIT_EXCEEDED;
            case "serverOverloaded" -> CodexPlatformErrorCode.CODEX_SERVER_OVERLOADED;
            case "cyberPolicy" -> CodexPlatformErrorCode.CODEX_CYBER_POLICY;
            case "internalServerError" -> CodexPlatformErrorCode.CODEX_INTERNAL_SERVER_ERROR;
            case "unauthorized" -> CodexPlatformErrorCode.CODEX_UNAUTHORIZED;
            case "badRequest" -> CodexPlatformErrorCode.CODEX_BAD_REQUEST;
            case "threadRollbackFailed" -> CodexPlatformErrorCode.CODEX_THREAD_ROLLBACK_FAILED;
            case "sandboxError" -> CodexPlatformErrorCode.CODEX_SANDBOX_ERROR;
            case "httpConnectionFailed" -> CodexPlatformErrorCode.CODEX_HTTP_CONNECTION_FAILED;
            case "responseStreamConnectionFailed" -> CodexPlatformErrorCode.CODEX_RESPONSE_STREAM_CONNECTION_FAILED;
            case "responseStreamDisconnected" -> CodexPlatformErrorCode.CODEX_RESPONSE_STREAM_DISCONNECTED;
            case "responseTooManyFailedAttempts" -> CodexPlatformErrorCode.CODEX_TOO_MANY_FAILED_ATTEMPTS;
            case "activeTurnNotSteerable" -> CodexPlatformErrorCode.CODEX_ACTIVE_TURN_NOT_STEERABLE;
            case "other" -> CodexPlatformErrorCode.CODEX_OTHER;
            default -> CodexPlatformErrorCode.CODEX_OTHER;
        };
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String redacted = value
                .replaceAll("(?i)sk-[A-Za-z0-9_-]+", "<redacted-api-key>")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1<redacted>")
                .replaceAll("(?i)(token|secret|authorization|password)(\\s*[:=]\\s*)\\S+", "$1$2<redacted>");
        return redacted.length() <= SUMMARY_MAX_LENGTH
                ? redacted
                : redacted.substring(0, SUMMARY_MAX_LENGTH) + "...";
    }

    private record NativeErrorInfo(String name, CodexPlatformErrorCode code, Integer httpStatusCode) {
    }
}
