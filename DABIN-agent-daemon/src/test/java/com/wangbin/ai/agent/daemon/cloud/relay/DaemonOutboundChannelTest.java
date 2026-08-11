package com.wangbin.ai.agent.daemon.cloud.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.websocket.PongPayload;
import com.wangbin.ai.agent.contract.websocket.WsEnvelope;
import com.wangbin.ai.agent.contract.websocket.WsMessageType;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonOutboundChannelTest {

    @Test
    void enqueueIsBoundedAndSendsSequentially() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setOutboundQueueCapacity(2);
        DaemonOutboundChannel channel = new DaemonOutboundChannel(objectMapper(), properties);
        ControlledWebSocket socket = new ControlledWebSocket();

        assertThat(channel.enqueue(socket, pong("1"), null)).isTrue();
        assertThat(channel.enqueue(socket, pong("2"), null)).isTrue();
        assertThat(channel.enqueue(socket, pong("3"), null)).isTrue();
        assertThat(channel.enqueue(socket, pong("4"), null)).isFalse();
        assertThat(socket.sentPayloads).hasSize(1);

        socket.completeNext();

        assertThat(socket.sentPayloads).hasSize(2);
    }

    @Test
    void sendFailureRunsFailureHandlerAndContinuesDraining() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setOutboundQueueCapacity(4);
        DaemonOutboundChannel channel = new DaemonOutboundChannel(objectMapper(), properties);
        ControlledWebSocket socket = new ControlledWebSocket();
        AtomicBoolean failed = new AtomicBoolean();

        assertThat(channel.enqueue(socket, pong("1"), () -> failed.set(true))).isTrue();
        assertThat(channel.enqueue(socket, pong("2"), null)).isTrue();

        socket.failNext();

        assertThat(failed).isTrue();
        assertThat(socket.sentPayloads).hasSize(2);
    }

    private WsEnvelope<PongPayload> pong(String id) {
        return WsEnvelope.of(WsMessageType.PONG, new PongPayload(id, Instant.now()));
    }

    private ObjectMapper objectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    private static final class ControlledWebSocket implements WebSocket {

        private final List<String> sentPayloads = new ArrayList<>();
        private final List<CompletableFuture<WebSocket>> sends = new ArrayList<>();

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sentPayloads.add(data.toString());
            CompletableFuture<WebSocket> future = new CompletableFuture<>();
            sends.add(future);
            return future;
        }

        private void completeNext() {
            sends.removeFirst().complete(this);
        }

        private void failNext() {
            sends.removeFirst().completeExceptionally(new IllegalStateException("send failed"));
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
    }
}
