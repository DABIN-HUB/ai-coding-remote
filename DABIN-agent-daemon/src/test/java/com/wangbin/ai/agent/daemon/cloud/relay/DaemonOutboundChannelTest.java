package com.wangbin.ai.agent.daemon.cloud.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.command.CommandAckStatus;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.agent.contract.protocol.AgentProtocol;
import com.wangbin.ai.agent.contract.websocket.HelloPayload;
import com.wangbin.ai.agent.contract.websocket.PongPayload;
import com.wangbin.ai.agent.contract.websocket.WsEnvelope;
import com.wangbin.ai.agent.contract.websocket.WsMessageType;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonOutboundChannelTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_COMMAND_ID = "cmd-1";
    private static final int REPLAY_RACE_REPETITIONS = 20;
    private static final long WAIT_TIMEOUT_SECONDS = 2L;

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

    @Test
    void reliableMessagesStayPendingAfterFailureReplayInOrderAndClearAfterSuccess() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setOutboundQueueCapacity(8);
        properties.setReliableOutboundCapacity(4);
        DaemonOutboundChannel channel = new DaemonOutboundChannel(objectMapper(), properties);
        ControlledWebSocket firstSocket = new ControlledWebSocket();
        ControlledWebSocket secondSocket = new ControlledWebSocket();
        AtomicBoolean failed = new AtomicBoolean();

        assertThat(channel.enqueue(firstSocket, ack(), OutboundReliability.RELIABLE, () -> {
            failed.set(true);
            channel.removeQueuedForSocket(firstSocket);
        })).isTrue();
        assertThat(channel.enqueue(firstSocket, sessionStarted("event-1", 1), OutboundReliability.RELIABLE, null))
                .isTrue();

        firstSocket.failNext();

        assertThat(failed).isTrue();
        assertThat(channel.reliablePendingSize()).isEqualTo(2);
        assertThat(channel.replayReliable(secondSocket, null)).isTrue();
        assertThat(secondSocket.sentPayloads).hasSize(1);
        assertThat(secondSocket.sentPayloads.get(0)).contains("\"type\":\"COMMAND_ACK\"", TEST_COMMAND_ID);

        secondSocket.completeNext();

        assertThat(secondSocket.sentPayloads).hasSize(2);
        assertThat(secondSocket.sentPayloads.get(1)).contains("\"type\":\"AGENT_EVENT\"", "\"seq\":1",
                "\"eventId\":\"event-1\"");

        secondSocket.completeNext();

        assertThat(channel.reliablePendingSize()).isZero();
        assertThat(channel.replayReliable(secondSocket, null)).isTrue();
        assertThat(secondSocket.sentPayloads).hasSize(2);
    }

    @Test
    void transientAndControlMessagesAreNotReplayed() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setOutboundQueueCapacity(4);
        DaemonOutboundChannel channel = new DaemonOutboundChannel(objectMapper(), properties);
        ControlledWebSocket firstSocket = new ControlledWebSocket();
        ControlledWebSocket secondSocket = new ControlledWebSocket();

        assertThat(channel.enqueue(firstSocket, pong("ping-1"), OutboundReliability.TRANSIENT, null)).isTrue();
        assertThat(channel.enqueue(firstSocket, delta("delta-1", 2), OutboundReliability.TRANSIENT, null)).isTrue();

        firstSocket.failNext();
        channel.removeQueuedForSocket(firstSocket);

        assertThat(channel.reliablePendingSize()).isZero();
        assertThat(channel.replayReliable(secondSocket, null)).isTrue();
        assertThat(secondSocket.sentPayloads).isEmpty();
    }

    @Test
    void reliableBufferIsBounded() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setReliableOutboundCapacity(1);
        DaemonOutboundChannel channel = new DaemonOutboundChannel(objectMapper(), properties);

        assertThat(channel.enqueue(null, sessionStarted("event-1", 1), OutboundReliability.RELIABLE, null)).isTrue();
        assertThat(channel.enqueue(null, sessionStarted("event-2", 2), OutboundReliability.RELIABLE, null)).isFalse();
        assertThat(channel.reliablePendingSize()).isEqualTo(1);
    }

    @RepeatedTest(REPLAY_RACE_REPETITIONS)
    void activationReplayBarrierKeepsPendingReliableBeforeConcurrentReliable() throws Exception {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setOutboundQueueCapacity(4);
        properties.setReliableOutboundCapacity(4);
        DaemonOutboundChannel channel = new DaemonOutboundChannel(objectMapper(), properties);
        ControlledWebSocket socket = new ControlledWebSocket();
        CountDownLatch concurrentSendStarted = new CountDownLatch(1);
        AtomicBoolean concurrentAccepted = new AtomicBoolean();
        AtomicReference<Throwable> concurrentFailure = new AtomicReference<>();
        Thread concurrentSender = new Thread(() -> {
            concurrentSendStarted.countDown();
            try {
                concurrentAccepted.set(channel.enqueue(null, sessionStarted("event-b", 11),
                        OutboundReliability.RELIABLE, null));
            } catch (Throwable ex) {
                concurrentFailure.set(ex);
            }
        }, "daemon-outbound-replay-race-test");

        assertThat(channel.enqueue(socket, hello(), OutboundReliability.TRANSIENT, null)).isTrue();
        assertThat(channel.enqueue(null, sessionStarted("event-a", 10), OutboundReliability.RELIABLE, null)).isTrue();

        assertThat(channel.activateConnectionAndReplay(socket, null, () -> {
            concurrentSender.start();
            await(concurrentSendStarted);
            waitUntilBlocked(concurrentSender);
        })).isTrue();
        concurrentSender.join(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS));

        assertThat(concurrentSender.isAlive()).isFalse();
        assertThat(concurrentFailure.get()).isNull();
        assertThat(concurrentAccepted).isTrue();
        assertThat(socket.sentPayloads).hasSize(1);
        assertThat(socket.sentPayloads.get(0)).contains("\"type\":\"HELLO\"");

        socket.completeNext();
        assertThat(socket.sentPayloads).hasSize(2);
        assertThat(socket.sentPayloads.get(1)).contains("\"eventId\":\"event-a\"", "\"seq\":10");

        socket.completeNext();
        assertThat(socket.sentPayloads).hasSize(3);
        assertThat(socket.sentPayloads.get(2)).contains("\"eventId\":\"event-b\"", "\"seq\":11");
    }

    private WsEnvelope<PongPayload> pong(String id) {
        return WsEnvelope.of(WsMessageType.PONG, new PongPayload(id, Instant.now()));
    }

    private WsEnvelope<HelloPayload> hello() {
        return WsEnvelope.of(WsMessageType.HELLO, new HelloPayload(AgentProtocol.VERSION, "ticket-1"));
    }

    private WsEnvelope<CommandAck> ack() {
        return WsEnvelope.of(WsMessageType.COMMAND_ACK, new CommandAck(TEST_COMMAND_ID, TEST_SESSION_ID,
                TEST_DEVICE_ID, CommandAckStatus.ACCEPTED, "ACCEPTED", "accepted", Instant.now(), Map.of()));
    }

    private WsEnvelope<AgentEvent> sessionStarted(String eventId, long seq) {
        return WsEnvelope.of(WsMessageType.AGENT_EVENT, new AgentEvent(eventId, "trace-1", TEST_TENANT_ID,
                TEST_USER_ID, TEST_DEVICE_ID, TEST_PROJECT_ID, TEST_SESSION_ID, seq, AgentType.CODEX,
                AgentEventType.SESSION_STARTED, null, Instant.now(),
                new SessionPayload("native-1", AgentSessionStatus.RUNNING, null, Map.of()), Map.of()));
    }

    private WsEnvelope<AgentEvent> delta(String eventId, long seq) {
        return WsEnvelope.of(WsMessageType.AGENT_EVENT, new AgentEvent(eventId, "trace-1", TEST_TENANT_ID,
                TEST_USER_ID, TEST_DEVICE_ID, TEST_PROJECT_ID, TEST_SESSION_ID, seq, AgentType.CODEX,
                AgentEventType.AGENT_MESSAGE_DELTA, null, Instant.now(),
                new AgentMessagePayload("msg-1", "assistant", "delta", true, Map.of()), Map.of()));
    }

    private ObjectMapper objectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("latch was not released before timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private void waitUntilBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.BLOCKED) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("thread did not block on channel activation monitor");
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
