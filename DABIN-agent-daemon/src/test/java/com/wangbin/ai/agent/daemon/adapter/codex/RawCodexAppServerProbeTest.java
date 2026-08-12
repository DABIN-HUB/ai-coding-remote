package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessageKind;
import com.wangbin.ai.agent.daemon.adapter.codex.protocol.CodexProtocolConstants;
import com.wangbin.ai.agent.daemon.process.DefaultProcessCommandResolver;
import com.wangbin.ai.agent.daemon.process.ResolvedCommand;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manual diagnostic probe for Codex app-server native behavior. It is skipped
 * in normal Maven runs and writes only sanitized protocol summaries under
 * target/codex-diagnostics.
 */
class RawCodexAppServerProbeTest {

    private static final String DIAGNOSTIC_ENABLED_PROPERTY = "codex.native.diagnostic";
    private static final String CODEX_HOME_PROPERTY = "codex.native.home";
    private static final String WORKSPACE_PROPERTY = "codex.native.workspace";
    private static final String EXECUTABLE_PROPERTY = "codex.native.executable";
    private static final String TIMEOUT_SECONDS_PROPERTY = "codex.native.timeoutSeconds";
    private static final String RUST_LOG_PROPERTY = "codex.native.rustLog";
    private static final String LOG_FORMAT_PROPERTY = "codex.native.logFormat";
    private static final String DEFAULT_TIMEOUT_SECONDS = "90";
    private static final String DEFAULT_PROMPT = "只回复 OK，不要调用任何工具";
    private static final String DEFAULT_RUST_LOG = "info";
    private static final String DEFAULT_LOG_FORMAT = "json";
    private static final int SUMMARY_MAX_LENGTH = 240;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rawCodexAppServerMinimalTurnProbe() throws Exception {
        assumeTrue(Boolean.getBoolean(DIAGNOSTIC_ENABLED_PROPERTY),
                "set -D" + DIAGNOSTIC_ENABLED_PROPERTY + "=true to run real Codex app-server diagnostic");
        Path diagnosticsDir = Path.of("target", "codex-diagnostics").toAbsolutePath().normalize();
        Files.createDirectories(diagnosticsDir);
        Path traceFile = diagnosticsDir.resolve("raw-app-server-probe-trace.jsonl");
        Path stderrFile = diagnosticsDir.resolve("raw-app-server-probe-stderr.log");
        Path workspace = Path.of(System.getProperty(WORKSPACE_PROPERTY, ".")).toAbsolutePath().normalize();
        Path codexHome = codexHome();
        Duration timeout = Duration.ofSeconds(Long.parseLong(
                System.getProperty(TIMEOUT_SECONDS_PROPERTY, DEFAULT_TIMEOUT_SECONDS)));
        ResolvedCommand command = new DefaultProcessCommandResolver()
                .resolve(System.getProperty(EXECUTABLE_PROPERTY, "codex"));
        List<String> argv = command.command(List.of("app-server", "--stdio"));
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(workspace.toFile());
        builder.environment().put("CODEX_HOME", codexHome.toString());
        builder.environment().put("RUST_LOG", System.getProperty(RUST_LOG_PROPERTY, DEFAULT_RUST_LOG));
        builder.environment().put("LOG_FORMAT", System.getProperty(LOG_FORMAT_PROPERTY, DEFAULT_LOG_FORMAT));
        Process process = builder.start();
        CopyOnWriteArrayList<String> traceLines = new CopyOnWriteArrayList<>();
        AtomicBoolean terminalReached = new AtomicBoolean(false);
        CountDownLatch terminalLatch = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             BufferedReader stdout = new BufferedReader(new InputStreamReader(
                     process.getInputStream(), StandardCharsets.UTF_8));
             OutputStreamWriter stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            executor.submit(() -> copyStderr(process, stderrFile));
            CodexJsonRpcClient client = new CodexJsonRpcClient(objectMapper, stdout, stdin, executor,
                    Duration.ofSeconds(30));
            client.messages().subscribe(message -> {
                traceLines.add(summary(message));
                if (CodexProtocolConstants.METHOD_TURN_COMPLETED.equals(message.method())) {
                    terminalReached.set(true);
                    terminalLatch.countDown();
                }
                if (message.kind() == CodexRpcMessageKind.SERVER_REQUEST) {
                    client.respondError(message.id(), CodexProtocolConstants.JSON_RPC_ROUTE_UNAVAILABLE,
                            "Raw diagnostic probe does not grant approvals");
                }
            });
            client.protocolIssues().subscribe(issue -> traceLines.add(issueSummary(issue.code(), issue.message())));
            client.start();
            try {
                initialize(client);
                String threadId = startThread(client, workspace);
                startTurn(client, threadId, workspace);
                terminalLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                traceLines.add(exceptionSummary(ex));
            } finally {
                client.close();
                terminateProcessTree(process);
                writeTrace(traceFile, traceLines);
            }
        }
        System.out.println("rawCodexProbeTrace=" + traceFile);
        System.out.println("rawCodexProbeStderr=" + stderrFile);
        System.out.println("rawCodexProbeTerminalReached=" + terminalReached.get());
    }

    private Path codexHome() {
        String configured = System.getProperty(CODEX_HOME_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("CODEX_HOME");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of("target", "smoke", "codex-home").toString();
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private void initialize(CodexJsonRpcClient client) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "ai_coding_remote_probe");
        clientInfo.put("title", "AI Coding Remote Raw Probe");
        clientInfo.put("version", "0.1.0");
        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.put("experimentalApi", true);
        client.request(CodexProtocolConstants.METHOD_INITIALIZE, params).get(30, TimeUnit.SECONDS);
        client.notify(CodexProtocolConstants.METHOD_INITIALIZED, null);
    }

    private String startThread(CodexJsonRpcClient client, Path workspace) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("cwd", workspace.toString());
        params.put("approvalPolicy", "on-request");
        JsonNode response = client.request(CodexProtocolConstants.METHOD_THREAD_START, params)
                .get(30, TimeUnit.SECONDS);
        return response.path("thread").path("id").asText();
    }

    private void startTurn(CodexJsonRpcClient client, String threadId, Path workspace) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("cwd", workspace.toString());
        params.put("clientUserMessageId", "diag-" + UUID.randomUUID());
        ArrayNode input = params.putArray("input");
        ObjectNode text = input.addObject();
        text.put("type", "text");
        text.put("text", DEFAULT_PROMPT);
        client.request(CodexProtocolConstants.METHOD_TURN_START, params).get(30, TimeUnit.SECONDS);
    }

    private void copyStderr(Process process, Path stderrFile) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = Files.newBufferedWriter(stderrFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(sanitize(line));
                writer.newLine();
            }
        } catch (Exception ignored) {
            // Diagnostic stderr capture must not hide the primary protocol result.
        }
    }

    private void writeTrace(Path traceFile, List<String> traceLines) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(traceFile, StandardCharsets.UTF_8)) {
            for (String line : traceLines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    private void terminateProcessTree(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private String summary(CodexRpcMessage message) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("timestamp", Instant.now().toString());
        summary.put("kind", message.kind().name());
        summary.put("method", message.method());
        JsonNode params = message.params();
        putText(summary, "threadId", text(params, "threadId"));
        putText(summary, "turnId", firstNonBlank(text(params, "turnId"), text(params == null ? null : params.path("turn"), "id")));
        putText(summary, "itemId", firstNonBlank(text(params, "itemId"), text(params == null ? null : params.path("item"), "id")));
        putText(summary, "itemType", text(params == null ? null : params.path("item"), "type"));
        putText(summary, "itemStatus", text(params == null ? null : params.path("item"), "status"));
        putText(summary, "turnStatus", text(params == null ? null : params.path("turn"), "status"));
        if (params != null && params.has("willRetry")) {
            summary.put("willRetry", params.path("willRetry").asBoolean());
        }
        JsonNode error = params == null ? null : params.path("error");
        if (error != null && !error.isMissingNode() && !error.isNull()) {
            putText(summary, "errorMessage", sanitize(text(error, "message")));
            putText(summary, "additionalDetails", sanitize(text(error, "additionalDetails")));
            appendErrorInfo(summary, error.path("codexErrorInfo"));
        }
        JsonNode turnError = params == null ? null : params.path("turn").path("error");
        if (turnError != null && !turnError.isMissingNode() && !turnError.isNull()) {
            putText(summary, "turnErrorMessage", sanitize(text(turnError, "message")));
            putText(summary, "turnAdditionalDetails", sanitize(text(turnError, "additionalDetails")));
            appendErrorInfo(summary, turnError.path("codexErrorInfo"));
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception ex) {
            return issueSummary("summary_failed", ex.getMessage());
        }
    }

    private String issueSummary(String code, String message) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("timestamp", Instant.now().toString());
        summary.put("kind", "PROTOCOL_ISSUE");
        summary.put("code", code);
        summary.put("message", sanitize(message));
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception ex) {
            return "{\"kind\":\"PROTOCOL_ISSUE\"}";
        }
    }

    private String exceptionSummary(Exception ex) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("timestamp", Instant.now().toString());
        summary.put("kind", "EXCEPTION");
        summary.put("type", ex.getClass().getSimpleName());
        summary.put("message", sanitize(ex.getMessage()));
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception ignored) {
            return "{\"kind\":\"EXCEPTION\"}";
        }
    }

    private void appendErrorInfo(ObjectNode summary, JsonNode errorInfo) {
        if (errorInfo == null || errorInfo.isMissingNode() || errorInfo.isNull()) {
            return;
        }
        if (errorInfo.isTextual()) {
            summary.put("codexErrorInfo", errorInfo.asText());
            return;
        }
        if (!errorInfo.isObject()) {
            summary.put("codexErrorInfo", errorInfo.asText());
            return;
        }
        errorInfo.fieldNames().forEachRemaining(name -> {
            summary.put("codexErrorInfo", name);
            JsonNode httpStatusCode = errorInfo.path(name).path("httpStatusCode");
            if (httpStatusCode.isNumber()) {
                summary.put("httpStatusCode", httpStatusCode.asInt());
            }
        });
    }

    private void putText(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value);
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String redacted = value
                .replaceAll("(?i)sk-[A-Za-z0-9_-]+", "<redacted-api-key>")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1<redacted>")
                .replaceAll("(?i)(token|secret|authorization|password)(\\s*[:=\\-]\\s*)\\S+", "$1$2<redacted>");
        return redacted.length() <= SUMMARY_MAX_LENGTH
                ? redacted
                : redacted.substring(0, SUMMARY_MAX_LENGTH) + "...";
    }
}
