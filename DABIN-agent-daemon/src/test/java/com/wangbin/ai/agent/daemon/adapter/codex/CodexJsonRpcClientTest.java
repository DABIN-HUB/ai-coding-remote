package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessageKind;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcProtocolIssue;
import com.wangbin.ai.agent.daemon.adapter.codex.protocol.CodexProtocolConstants;
import com.wangbin.ai.agent.daemon.exception.AgentConnectionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodexJsonRpcClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void correlatesRequestResponseById() throws Exception {
        StringWriter writer = new StringWriter();
        CodexJsonRpcClient client = new CodexJsonRpcClient(objectMapper, null, writer, executor,
                Duration.ofSeconds(5));

        var future = client.request("initialize", objectMapper.createObjectNode().put("client", "test"));
        JsonNode request = objectMapper.readTree(writer.toString().trim());
        assertThat(request.has("jsonrpc")).isFalse();

        client.handleLine("{\"id\":\"" + request.get("id").asText() + "\",\"result\":{\"ok\":true}}");

        assertThat(future.join().get("ok").asBoolean()).isTrue();
    }

    @Test
    void omitsJsonRpcHeaderForNotificationsAndServerResponses() throws Exception {
        StringWriter writer = new StringWriter();
        CodexJsonRpcClient client = new CodexJsonRpcClient(objectMapper, null, writer, executor,
                Duration.ofSeconds(5));

        client.notify(CodexProtocolConstants.METHOD_INITIALIZED, null);
        client.respond(objectMapper.getNodeFactory().textNode("server-1"),
                objectMapper.createObjectNode().put("ok", true));
        client.respondError(objectMapper.getNodeFactory().textNode("server-2"),
                CodexProtocolConstants.JSON_RPC_METHOD_NOT_FOUND, "unsupported");

        String[] lines = writer.toString().trim().split("\\R");
        assertThat(lines).hasSize(3);
        assertThat(objectMapper.readTree(lines[0]).has("jsonrpc")).isFalse();
        assertThat(objectMapper.readTree(lines[1]).has("jsonrpc")).isFalse();
        assertThat(objectMapper.readTree(lines[2]).has("jsonrpc")).isFalse();
    }

    @Test
    void emitsNotificationMessages() {
        StringWriter writer = new StringWriter();
        CodexJsonRpcClient client = new CodexJsonRpcClient(objectMapper, null, writer, executor,
                Duration.ofSeconds(5));
        List<CodexRpcMessage> messages = new ArrayList<>();
        client.messages().subscribe(messages::add);

        client.handleLine("{\"method\":\"thread/started\",\"params\":{\"threadId\":\"native-1\"}}");

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().kind()).isEqualTo(CodexRpcMessageKind.NOTIFICATION);
        assertThat(messages.getFirst().method()).isEqualTo("thread/started");
    }

    @Test
    void emitsServerInitiatedRequests() {
        StringWriter writer = new StringWriter();
        CodexJsonRpcClient client = new CodexJsonRpcClient(objectMapper, null, writer, executor,
                Duration.ofSeconds(5));
        List<CodexRpcMessage> messages = new ArrayList<>();
        client.messages().subscribe(messages::add);

        client.handleLine("{\"id\":\"approval-1\",\"method\":\"item/permissions/requestApproval\",\"params\":{}}");

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().kind()).isEqualTo(CodexRpcMessageKind.SERVER_REQUEST);
        assertThat(messages.getFirst().idText()).isEqualTo("approval-1");
        assertThat(messages.getFirst().method()).isEqualTo("item/permissions/requestApproval");
    }

    @Test
    void preservesNumericServerRequestIdWhenResponding() throws Exception {
        StringWriter writer = new StringWriter();
        CodexJsonRpcClient client = new CodexJsonRpcClient(objectMapper, null, writer, executor,
                Duration.ofSeconds(5));
        List<CodexRpcMessage> messages = new ArrayList<>();
        client.messages().subscribe(messages::add);

        client.handleLine("{\"id\":61,\"method\":\"item/permissions/requestApproval\",\"params\":{}}");
        client.respond(messages.getFirst().id(), objectMapper.createObjectNode().put("ok", true));

        JsonNode response = objectMapper.readTree(writer.toString().trim());
        assertThat(response.get("id").isNumber()).isTrue();
        assertThat(response.get("id").asInt()).isEqualTo(61);
    }

    @Test
    void preservesStringServerRequestIdWhenRespondingError() throws Exception {
        StringWriter writer = new StringWriter();
        CodexJsonRpcClient client = new CodexJsonRpcClient(objectMapper, null, writer, executor,
                Duration.ofSeconds(5));
        List<CodexRpcMessage> messages = new ArrayList<>();
        client.messages().subscribe(messages::add);

        client.handleLine("{\"id\":\"61\",\"method\":\"item/permissions/requestApproval\",\"params\":{}}");
        client.respondError(messages.getFirst().id(), CodexProtocolConstants.JSON_RPC_METHOD_NOT_FOUND,
                "unsupported");

        JsonNode response = objectMapper.readTree(writer.toString().trim());
        assertThat(response.get("id").isTextual()).isTrue();
        assertThat(response.get("id").asText()).isEqualTo("61");
    }

    @Test
    void reportsUnknownAndMalformedMessages() {
        StringWriter writer = new StringWriter();
        CodexJsonRpcClient client = new CodexJsonRpcClient(objectMapper, null, writer, executor,
                Duration.ofSeconds(5));
        List<CodexRpcProtocolIssue> issues = new ArrayList<>();
        client.protocolIssues().subscribe(issues::add);

        client.handleLine("{\"jsonrpc\":\"2.0\"}");
        client.handleLine("not-json");

        assertThat(issues).extracting(CodexRpcProtocolIssue::code)
                .containsExactly("unknown_message", "malformed_message");
    }

    @Test
    void completesPendingRequestsOnProcessExit() {
        StringWriter writer = new StringWriter();
        CodexJsonRpcClient client = new CodexJsonRpcClient(objectMapper, null, writer, executor,
                Duration.ofSeconds(5));
        var future = client.request("thread/start", objectMapper.createObjectNode());

        client.closeWithError(new AgentConnectionException("process exited", null));

        assertThatThrownBy(future::join).hasCauseInstanceOf(AgentConnectionException.class);
    }

}
