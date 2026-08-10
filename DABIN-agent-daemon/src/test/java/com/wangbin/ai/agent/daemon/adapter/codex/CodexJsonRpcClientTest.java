package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessage;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcMessageKind;
import com.wangbin.ai.agent.daemon.adapter.codex.model.CodexRpcProtocolIssue;
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

        client.handleLine("{\"id\":\"" + request.get("id").asText() + "\",\"result\":{\"ok\":true}}");

        assertThat(future.join().get("ok").asBoolean()).isTrue();
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
        assertThat(messages.getFirst().id()).isEqualTo("approval-1");
        assertThat(messages.getFirst().method()).isEqualTo("item/permissions/requestApproval");
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
