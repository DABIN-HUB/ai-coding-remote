package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcProtocolIssue;
import com.wangbin.ai.agent.daemon.exception.AgentConnectionException;
import com.wangbin.ai.agent.daemon.exception.AgentProtocolException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class CodexJsonRpcClient implements AutoCloseable {

    private final ObjectMapper objectMapper;
    private final BufferedReader reader;
    private final Writer writer;
    private final ExecutorService ioExecutor;
    private final Duration requestTimeout;
    private final AtomicLong requestSequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final Sinks.Many<CodexRpcMessage> messages = Sinks.many().multicast().directBestEffort();
    private final Sinks.Many<CodexRpcProtocolIssue> protocolIssues = Sinks.many().multicast().directBestEffort();

    public CodexJsonRpcClient(ObjectMapper objectMapper, BufferedReader reader, Writer writer,
                              ExecutorService ioExecutor, Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.reader = reader;
        this.writer = writer;
        this.ioExecutor = ioExecutor;
        this.requestTimeout = requestTimeout;
    }

    public void start() {
        if (reader == null) {
            return;
        }
        ioExecutor.submit(this::readLoop);
    }

    public CompletableFuture<JsonNode> request(String method, Object params) {
        String requestId = Long.toString(requestSequence.incrementAndGet());
        JsonNode requestIdNode = objectMapper.getNodeFactory().textNode(requestId);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(idKey(requestIdNode), future);
        ObjectNode request = messageObject();
        request.set("id", requestIdNode);
        request.put("method", method);
        if (params != null) {
            request.set("params", objectMapper.valueToTree(params));
        }
        try {
            writeJson(request);
        } catch (RuntimeException ex) {
            pendingRequests.remove(idKey(requestIdNode));
            future.completeExceptionally(ex);
            throw ex;
        }
        future.orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, throwable) -> pendingRequests.remove(idKey(requestIdNode)));
        return future;
    }

    public void notify(String method, Object params) {
        ObjectNode notification = messageObject();
        notification.put("method", method);
        if (params != null) {
            notification.set("params", objectMapper.valueToTree(params));
        }
        writeJson(notification);
    }

    public void respond(JsonNode id, Object result) {
        ObjectNode response = messageObject();
        response.set("id", id == null ? objectMapper.nullNode() : id.deepCopy());
        response.set("result", objectMapper.valueToTree(result == null ? Map.of() : result));
        writeJson(response);
    }

    public void respondError(JsonNode id, int code, String message) {
        ObjectNode response = messageObject();
        response.set("id", id == null ? objectMapper.nullNode() : id.deepCopy());
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        writeJson(response);
    }

    public Flux<CodexRpcMessage> messages() {
        return messages.asFlux();
    }

    public Flux<CodexRpcProtocolIssue> protocolIssues() {
        return protocolIssues.asFlux();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public int pendingRequestCount() {
        return pendingRequests.size();
    }

    public void protocolWarning(String code, String message, String rawLine) {
        emitIssue(code, message, rawLine);
    }

    void handleLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(line);
            JsonNode idNode = node.get("id");
            JsonNode id = idNode == null || idNode.isNull() ? null : idNode.deepCopy();
            JsonNode methodNode = node.get("method");
            String method = methodNode == null || methodNode.isNull() ? null : methodNode.asText();
            if (id != null && method != null) {
                emit(CodexRpcMessage.serverRequest(id, method, node.get("params")));
            } else if (method != null) {
                emit(CodexRpcMessage.notification(method, node.get("params")));
            } else if (id != null && node.has("result")) {
                completeResponse(id, node.get("result"));
            } else if (id != null && node.has("error")) {
                completeError(id, node.get("error"));
            } else {
                emitIssue("unknown_message", "unknown JSON-RPC message shape", line);
            }
        } catch (Exception ex) {
            emitIssue("malformed_message", ex.getMessage(), line);
        }
    }

    public void closeWithError(Throwable throwable) {
        closed.set(true);
        pendingRequests.forEach((id, future) -> future.completeExceptionally(throwable));
        pendingRequests.clear();
        messages.tryEmitComplete();
        protocolIssues.tryEmitComplete();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeWithError(new AgentConnectionException("codex JSON-RPC client closed", null));
        }
    }

    private void readLoop() {
        try {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                handleLine(line);
            }
            closeWithError(new AgentConnectionException("codex app-server stdout closed", null));
        } catch (IOException ex) {
            closeWithError(new AgentConnectionException("failed to read codex app-server stdout", ex));
        }
    }

    private void completeResponse(JsonNode id, JsonNode result) {
        CompletableFuture<JsonNode> future = pendingRequests.remove(idKey(id));
        if (future == null) {
            emitIssue("unknown_response", "response id has no pending request: " + id, null);
            return;
        }
        future.complete(result);
    }

    private void completeError(JsonNode id, JsonNode error) {
        CompletableFuture<JsonNode> future = pendingRequests.remove(idKey(id));
        if (future == null) {
            emitIssue("unknown_error_response", "error response id has no pending request: " + id, null);
            return;
        }
        String message = error != null && error.has("message") ? error.get("message").asText() : "JSON-RPC error";
        future.completeExceptionally(new AgentProtocolException(message));
    }

    private void emit(CodexRpcMessage message) {
        messages.tryEmitNext(message);
    }

    private void emitIssue(String code, String message, String rawLine) {
        protocolIssues.tryEmitNext(new CodexRpcProtocolIssue(code, message, rawLine));
    }

    private synchronized void writeJson(JsonNode node) {
        if (closed.get()) {
            throw new AgentConnectionException("codex JSON-RPC client is closed", null);
        }
        try {
            writer.write(objectMapper.writeValueAsString(node));
            writer.write(System.lineSeparator());
            writer.flush();
        } catch (IOException ex) {
            throw new AgentConnectionException("failed to write JSON-RPC message", ex);
        }
    }

    private ObjectNode messageObject() {
        return objectMapper.createObjectNode();
    }

    private String idKey(JsonNode id) {
        return id == null || id.isNull() ? "null" : id.toString();
    }

}
