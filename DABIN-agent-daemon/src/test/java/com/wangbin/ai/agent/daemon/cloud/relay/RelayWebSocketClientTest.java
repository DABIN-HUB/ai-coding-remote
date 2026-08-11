package com.wangbin.ai.agent.daemon.cloud.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.command.CommandAckStatus;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.agent.contract.websocket.WelcomePayload;
import com.wangbin.ai.agent.contract.websocket.WsEnvelope;
import com.wangbin.ai.agent.contract.websocket.WsMessageType;
import com.wangbin.ai.agent.daemon.cloud.controlplane.ControlPlaneClient;
import com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceRequest;
import com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceResponse;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RelayTicketResponse;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RelayWebSocketClientTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_CREDENTIAL_ID = "cred-1";
    private static final String TEST_CREDENTIAL_SECRET = "secret";
    private static final String TEST_RELAY_URL = "ws://127.0.0.1:48180/agent/ws";
    private static final String TEST_RELAY_NODE_ID = "relay-1";
    private static final String TEST_CONNECTION_ID = "conn-1";
    private static final String TEST_CONNECTION_ID_SECOND = "conn-2";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_COMMAND_ID = "cmd-1";
    private static final String TEST_RELAY_TICKET_PREFIX = "ticket-";
    private static final String NETWORK_FAILURE_MESSAGE = "network";
    private static final long TEST_RELAY_TICKET_TTL_SECONDS = 60L;
    private static final Duration TEST_RECONNECT_DELAY = Duration.ofMillis(20);
    private static final Duration TEST_WELCOME_TIMEOUT = Duration.ofMillis(30);
    private static final Duration SLOW_WELCOME_TIMEOUT = Duration.ofMillis(120);
    private static final Duration CAPTURED_WELCOME_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TEST_HEARTBEAT_INTERVAL = Duration.ofSeconds(20);
    private static final long WAIT_TIMEOUT_SECONDS = 2L;
    private static final Duration WAIT_POLL_INTERVAL = Duration.ofMillis(10);
    private static final int ABNORMAL_CLOSE_STATUS = 1006;
    private static final int REPLAY_RACE_REPETITIONS = 10;

    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void onErrorAndOnCloseScheduleOnlyOneReconnect() {
        AgentDaemonProperties properties = properties();
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        FakeConnector connector = new FakeConnector(false);
        RelayWebSocketClient client = new RelayWebSocketClient(objectMapper, properties,
                controlPlaneClient, scheduler, connector);

        client.start(credential());
        waitUntil(() -> connector.connectCount.get() == 1);
        Attempt firstAttempt = connector.attempt(0);
        waitUntilCurrentSocket(client, firstAttempt.socket());

        firstAttempt.listener().onError(firstAttempt.socket(), new RuntimeException(NETWORK_FAILURE_MESSAGE));
        firstAttempt.listener().onClose(firstAttempt.socket(), ABNORMAL_CLOSE_STATUS, "closed");

        waitUntil(() -> connector.connectCount.get() == 2);
        assertThat(controlPlaneClient.ticketCount.get()).isEqualTo(2);
        client.stop();
    }

    @Test
    void helloSendFailureReconnectsWithNewRelayTicket() {
        AgentDaemonProperties properties = properties();
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        FakeConnector connector = new FakeConnector(true);
        RelayWebSocketClient client = new RelayWebSocketClient(objectMapper, properties,
                controlPlaneClient, scheduler, connector);

        client.start(credential());

        waitUntil(() -> controlPlaneClient.ticketCount.get() >= 2 && connector.connectCount.get() >= 2);
        assertThat(connector.connectCount.get()).isGreaterThanOrEqualTo(2);
        client.stop();
    }

    @Test
    void welcomeTimeoutReconnectsAndWelcomeCancelsAuthTimeout() throws Exception {
        AgentDaemonProperties properties = properties();
        properties.getCloud().setWelcomeTimeout(SLOW_WELCOME_TIMEOUT);
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        FakeConnector connector = new FakeConnector(false);
        RelayWebSocketClient client = new RelayWebSocketClient(objectMapper, properties,
                controlPlaneClient, scheduler, connector);

        client.start(credential());
        waitUntil(() -> controlPlaneClient.ticketCount.get() >= 2 && connector.connectCount.get() >= 2);
        Attempt latestAttempt = connector.latestAttempt();
        waitUntilCurrentSocket(client, latestAttempt.socket());
        String welcome = objectMapper.writeValueAsString(WsEnvelope.of(WsMessageType.WELCOME,
                new WelcomePayload(TEST_CONNECTION_ID, TEST_RELAY_NODE_ID, TEST_HEARTBEAT_INTERVAL, Instant.now())));

        latestAttempt.listener().onText(latestAttempt.socket(), welcome, true);
        waitUntil(() -> client.state() == RelayConnectionState.CONNECTED);
        int ticketCountAfterWelcome = controlPlaneClient.ticketCount.get();
        sleep(properties.getCloud().getWelcomeTimeout().multipliedBy(2));

        assertThat(controlPlaneClient.ticketCount.get()).isEqualTo(ticketCountAfterWelcome);
        client.stop();
    }

    @Test
    void staleCloseAfterNextAttemptStartedDoesNotCreateThirdAttemptOrClearInFlight() throws Exception {
        AgentDaemonProperties properties = properties();
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        FakeConnector connector = new FakeConnector(false);
        RelayWebSocketClient client = new RelayWebSocketClient(objectMapper, properties,
                controlPlaneClient, scheduler, connector);

        client.start(credential());
        waitUntil(() -> connector.connectCount.get() == 1);
        Attempt firstAttempt = connector.attempt(0);
        waitUntilCurrentSocket(client, firstAttempt.socket());
        connector.completeConnectImmediately.set(false);
        firstAttempt.listener().onError(firstAttempt.socket(), new RuntimeException(NETWORK_FAILURE_MESSAGE));
        waitUntil(() -> connector.connectCount.get() == 2 && controlPlaneClient.ticketCount.get() == 2);

        firstAttempt.listener().onClose(firstAttempt.socket(), ABNORMAL_CLOSE_STATUS, "late close");
        sleep(properties.getCloud().getReconnectInitialDelay().multipliedBy(3));

        assertThat(connector.connectCount.get()).isEqualTo(2);
        assertThat(connectInFlight(client)).isTrue();
        client.stop();
    }

    @Test
    void staleAttemptCallbacksAreIgnoredAfterNewAttemptConnected() throws Exception {
        AgentDaemonProperties properties = properties();
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        FakeConnector connector = new FakeConnector(false);
        RelayWebSocketClient client = new RelayWebSocketClient(objectMapper, properties,
                controlPlaneClient, scheduler, connector);

        client.start(credential());
        waitUntil(() -> connector.connectCount.get() == 1);
        Attempt firstAttempt = connector.attempt(0);
        waitUntilCurrentSocket(client, firstAttempt.socket());
        firstAttempt.listener().onError(firstAttempt.socket(), new RuntimeException(NETWORK_FAILURE_MESSAGE));
        waitUntil(() -> connector.connectCount.get() == 2);
        sendWelcome(connector.latestAttempt(), TEST_CONNECTION_ID_SECOND);
        waitUntil(() -> client.state() == RelayConnectionState.CONNECTED);
        int ticketCountAfterConnected = controlPlaneClient.ticketCount.get();

        sendWelcome(firstAttempt, "stale");
        firstAttempt.listener().onError(firstAttempt.socket(), new RuntimeException("stale error"));
        firstAttempt.listener().onClose(firstAttempt.socket(), ABNORMAL_CLOSE_STATUS, "stale close");
        sleep(properties.getCloud().getReconnectInitialDelay().multipliedBy(3));

        assertThat(client.state()).isEqualTo(RelayConnectionState.CONNECTED);
        assertThat(controlPlaneClient.ticketCount.get()).isEqualTo(ticketCountAfterConnected);
        client.stop();
    }

    @Test
    void staleWelcomeTimeoutDoesNotDisconnectNewConnectedAttempt() throws Exception {
        AgentDaemonProperties properties = properties();
        properties.getCloud().setWelcomeTimeout(CAPTURED_WELCOME_TIMEOUT);
        CaptureScheduledExecutor captureScheduler = new CaptureScheduledExecutor(properties.getCloud().getWelcomeTimeout());
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        FakeConnector connector = new FakeConnector(false);
        RelayWebSocketClient client = new RelayWebSocketClient(objectMapper, properties,
                controlPlaneClient, captureScheduler, connector);
        try {
            client.start(credential());
            waitUntil(() -> connector.connectCount.get() == 1);
            waitUntil(() -> captureScheduler.latestAuthTimeout.get() != null);
            Runnable firstAuthTimeout = captureScheduler.latestAuthTimeout.get();
            Attempt firstAttempt = connector.attempt(0);
            waitUntilCurrentSocket(client, firstAttempt.socket());
            firstAttempt.listener().onError(firstAttempt.socket(), new RuntimeException(NETWORK_FAILURE_MESSAGE));
            waitUntil(() -> connector.connectCount.get() == 2);
            sendWelcome(connector.latestAttempt(), TEST_CONNECTION_ID_SECOND);
            waitUntil(() -> client.state() == RelayConnectionState.CONNECTED);
            int ticketCountAfterConnected = controlPlaneClient.ticketCount.get();

            firstAuthTimeout.run();
            sleep(properties.getCloud().getReconnectInitialDelay().multipliedBy(2));

            assertThat(client.state()).isEqualTo(RelayConnectionState.CONNECTED);
            assertThat(controlPlaneClient.ticketCount.get()).isEqualTo(ticketCountAfterConnected);
        } finally {
            client.stop();
            captureScheduler.shutdownNow();
        }
    }

    @Test
    void reliableBusinessMessagesReplayAfterReconnectWithOriginalOrderAndSequence() throws Exception {
        AgentDaemonProperties properties = properties();
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        FakeConnector connector = new FakeConnector(false);
        RelayWebSocketClient client = new RelayWebSocketClient(objectMapper, properties,
                controlPlaneClient, scheduler, connector);

        client.start(credential());
        waitUntil(() -> connector.connectCount.get() == 1);
        Attempt firstAttempt = connector.attempt(0);
        waitUntilCurrentSocket(client, firstAttempt.socket());
        sendWelcome(firstAttempt, TEST_CONNECTION_ID);
        waitUntil(() -> client.state() == RelayConnectionState.CONNECTED);
        firstAttempt.socket().failNextCommandAck.set(true);

        assertThat(client.sendCommandAck(ack())).isTrue();
        waitUntil(() -> connector.connectCount.get() == 2);
        assertThat(client.sendAgentEvent(sessionEvent("event-started", 1, AgentEventType.SESSION_STARTED))).isTrue();
        assertThat(client.sendAgentEvent(sessionEvent("event-final", 6, AgentEventType.SESSION_IDLE))).isTrue();
        assertThat(client.sendAgentEvent(deltaEvent("event-delta", 2))).isFalse();

        Attempt secondAttempt = connector.latestAttempt();
        sendWelcome(secondAttempt, TEST_CONNECTION_ID_SECOND);
        waitUntil(() -> secondAttempt.socket().sentPayloads.size() >= 4);

        assertThat(secondAttempt.socket().sentPayloads.get(0)).contains("\"type\":\"HELLO\"");
        assertThat(secondAttempt.socket().sentPayloads.get(1)).contains("\"type\":\"COMMAND_ACK\"", TEST_COMMAND_ID);
        assertThat(secondAttempt.socket().sentPayloads.get(2)).contains("\"type\":\"AGENT_EVENT\"",
                "\"eventId\":\"event-started\"", "\"seq\":1");
        assertThat(secondAttempt.socket().sentPayloads.get(3)).contains("\"type\":\"AGENT_EVENT\"",
                "\"eventId\":\"event-final\"", "\"seq\":6");
        assertThat(secondAttempt.socket().sentPayloads)
                .noneSatisfy(payload -> assertThat(payload).contains("event-delta"));

        int sentAfterReplay = secondAttempt.socket().sentPayloads.size();
        secondAttempt.listener().onError(secondAttempt.socket(), new RuntimeException(NETWORK_FAILURE_MESSAGE));
        waitUntil(() -> connector.connectCount.get() == 3);
        sendWelcome(connector.latestAttempt(), "conn-3");
        sleep(properties.getCloud().getReconnectInitialDelay().multipliedBy(2));

        String thirdAttemptPayloads = String.join("\n", connector.latestAttempt().socket().sentPayloads);
        assertThat(thirdAttemptPayloads)
                .doesNotContain("event-started")
                .doesNotContain("event-final")
                .doesNotContain(TEST_COMMAND_ID);
        assertThat(sentAfterReplay).isEqualTo(4);
        client.stop();
    }

    @RepeatedTest(REPLAY_RACE_REPETITIONS)
    void welcomeReplayBarrierPreventsNewReliableEventFromPassingOldPendingEvent() throws Exception {
        AgentDaemonProperties properties = properties();
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        FakeConnector connector = new FakeConnector(false);
        RaceActivationChannel outboundChannel = new RaceActivationChannel(objectMapper, properties);
        RelayWebSocketClient client = new RelayWebSocketClient(objectMapper, properties, controlPlaneClient,
                scheduler, (command, credential, outboundSender) -> {
                }, outboundChannel, connector);
        CountDownLatch concurrentSendStarted = new CountDownLatch(1);
        AtomicBoolean concurrentAccepted = new AtomicBoolean();
        AtomicReference<Throwable> concurrentFailure = new AtomicReference<>();
        Thread concurrentSender = new Thread(() -> {
            concurrentSendStarted.countDown();
            try {
                concurrentAccepted.set(client.sendAgentEvent(sessionEvent("event-b", 11,
                        AgentEventType.SESSION_IDLE)));
            } catch (Throwable ex) {
                concurrentFailure.set(ex);
            }
        }, "relay-welcome-replay-race-test");
        try {
            client.start(credential());
            waitUntil(() -> connector.connectCount.get() == 1);
            Attempt firstAttempt = connector.attempt(0);
            waitUntilCurrentSocket(client, firstAttempt.socket());
            sendWelcome(firstAttempt, TEST_CONNECTION_ID);
            waitUntil(() -> client.state() == RelayConnectionState.CONNECTED);

            firstAttempt.listener().onError(firstAttempt.socket(), new RuntimeException(NETWORK_FAILURE_MESSAGE));
            waitUntil(() -> connector.connectCount.get() == 2);
            assertThat(client.sendAgentEvent(sessionEvent("event-a", 10, AgentEventType.SESSION_STARTED))).isTrue();
            outboundChannel.beforeReplayHook = () -> {
                concurrentSender.start();
                await(concurrentSendStarted);
                waitUntilBlocked(concurrentSender);
            };

            Attempt secondAttempt = connector.latestAttempt();
            sendWelcome(secondAttempt, TEST_CONNECTION_ID_SECOND);
            waitUntil(() -> client.state() == RelayConnectionState.CONNECTED);
            concurrentSender.join(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS));

            assertThat(concurrentSender.isAlive()).isFalse();
            assertThat(concurrentFailure.get()).isNull();
            assertThat(concurrentAccepted).isTrue();
            assertThat(secondAttempt.socket().sentPayloads).hasSize(3);
            assertThat(secondAttempt.socket().sentPayloads.get(0)).contains("\"type\":\"HELLO\"");
            assertThat(secondAttempt.socket().sentPayloads.get(1)).contains("\"eventId\":\"event-a\"", "\"seq\":10");
            assertThat(secondAttempt.socket().sentPayloads.get(2)).contains("\"eventId\":\"event-b\"", "\"seq\":11");
        } finally {
            client.stop();
        }
    }

    private AgentDaemonProperties properties() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.getCloud().setReconnectInitialDelay(TEST_RECONNECT_DELAY);
        properties.getCloud().setReconnectMaxDelay(TEST_RECONNECT_DELAY);
        properties.getCloud().setWelcomeTimeout(TEST_WELCOME_TIMEOUT);
        return properties;
    }

    private DeviceCredentialState credential() {
        DeviceCredentialState credential = new DeviceCredentialState();
        credential.setTenantId(TEST_TENANT_ID);
        credential.setDeviceId(TEST_DEVICE_ID);
        credential.setCredentialId(TEST_CREDENTIAL_ID);
        credential.setCredentialSecret(TEST_CREDENTIAL_SECRET);
        credential.setRelayUrl(TEST_RELAY_URL);
        return credential;
    }

    private void waitUntil(BooleanCondition condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.matches()) {
                return;
            }
            sleep(WAIT_POLL_INTERVAL);
        }
        throw new AssertionError("condition was not satisfied before timeout");
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
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

    private void sendWelcome(Attempt attempt, String connectionId) throws Exception {
        String welcome = objectMapper.writeValueAsString(WsEnvelope.of(WsMessageType.WELCOME,
                new WelcomePayload(connectionId, TEST_RELAY_NODE_ID, TEST_HEARTBEAT_INTERVAL, Instant.now())));
        attempt.listener().onText(attempt.socket(), welcome, true);
    }

    private CommandAck ack() {
        return new CommandAck(TEST_COMMAND_ID, TEST_SESSION_ID, TEST_DEVICE_ID, CommandAckStatus.ACCEPTED,
                "ACCEPTED", "accepted", Instant.now(), java.util.Map.of());
    }

    private AgentEvent sessionEvent(String eventId, long seq, AgentEventType type) {
        return new AgentEvent(eventId, "trace-1", TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID, TEST_PROJECT_ID,
                TEST_SESSION_ID, seq, AgentType.CODEX, type, EventPriority.IMPORTANT, Instant.now(),
                new SessionPayload("native-1", type == AgentEventType.SESSION_IDLE
                        ? AgentSessionStatus.IDLE : AgentSessionStatus.RUNNING, null, java.util.Map.of()),
                java.util.Map.of());
    }

    private AgentEvent deltaEvent(String eventId, long seq) {
        return new AgentEvent(eventId, "trace-1", TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID, TEST_PROJECT_ID,
                TEST_SESSION_ID, seq, AgentType.CODEX, AgentEventType.AGENT_MESSAGE_DELTA, null, Instant.now(),
                new AgentMessagePayload("msg-1", "assistant", "delta", true, java.util.Map.of()), java.util.Map.of());
    }

    private boolean connectInFlight(RelayWebSocketClient client) throws Exception {
        Field field = RelayWebSocketClient.class.getDeclaredField("connectInFlight");
        field.setAccessible(true);
        return field.getBoolean(client);
    }

    private void waitUntilCurrentSocket(RelayWebSocketClient client, WebSocket socket) {
        waitUntil(() -> currentSocket(client) == socket);
    }

    private WebSocket currentSocket(RelayWebSocketClient client) {
        try {
            Field field = RelayWebSocketClient.class.getDeclaredField("webSocket");
            field.setAccessible(true);
            return (WebSocket) field.get(client);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    @FunctionalInterface
    private interface BooleanCondition {

        boolean matches();

    }

    private static final class FakeControlPlaneClient implements ControlPlaneClient {

        private final AtomicInteger ticketCount = new AtomicInteger();

        @Override
        public PairDeviceResponse pair(String controlPlaneUrl, PairDeviceRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RelayTicketResponse createDeviceRelayTicket(DeviceCredentialState credential) {
            int count = ticketCount.incrementAndGet();
            return new RelayTicketResponse(TEST_RELAY_TICKET_PREFIX + count,
                    Instant.now().plusSeconds(TEST_RELAY_TICKET_TTL_SECONDS));
        }
    }

    private static final class FakeConnector implements RelayWebSocketConnector {

        private final boolean failSend;
        private final AtomicBoolean failNextCommandAck = new AtomicBoolean();
        private final List<String> sentPayloads = new CopyOnWriteArrayList<>();
        private final AtomicInteger connectCount = new AtomicInteger();
        private final AtomicBoolean completeConnectImmediately = new AtomicBoolean(true);
        private final List<Attempt> attempts = new CopyOnWriteArrayList<>();

        private FakeConnector(boolean failSend) {
            this.failSend = failSend;
        }

        @Override
        public CompletableFuture<WebSocket> connect(URI relayUri, WebSocket.Listener webSocketListener) {
            int attemptNumber = connectCount.get() + 1;
            FakeWebSocket fakeWebSocket = new FakeWebSocket(failSend);
            CompletableFuture<WebSocket> future = new CompletableFuture<>();
            attempts.add(new Attempt(attemptNumber, webSocketListener, fakeWebSocket, future));
            connectCount.incrementAndGet();
            webSocketListener.onOpen(fakeWebSocket);
            if (completeConnectImmediately.get()) {
                future.complete(fakeWebSocket);
            }
            return future;
        }

        private Attempt attempt(int index) {
            return attempts.get(index);
        }

        private Attempt latestAttempt() {
            return attempts.get(attempts.size() - 1);
        }
    }

    private record Attempt(int attemptNumber, WebSocket.Listener listener, FakeWebSocket socket,
                           CompletableFuture<WebSocket> future) {
    }

    private static final class RaceActivationChannel extends DaemonOutboundChannel {

        private volatile Runnable beforeReplayHook;

        private RaceActivationChannel(ObjectMapper objectMapper, AgentDaemonProperties properties) {
            super(objectMapper, properties);
        }

        @Override
        public boolean activateConnectionAndReplay(WebSocket socket, Runnable failureHandler) {
            return super.activateConnectionAndReplay(socket, failureHandler, beforeReplayHook);
        }
    }

    private static final class CaptureScheduledExecutor extends ScheduledThreadPoolExecutor {

        private final Duration authTimeoutDelay;
        private final AtomicReference<Runnable> latestAuthTimeout = new AtomicReference<>();

        private CaptureScheduledExecutor(Duration authTimeoutDelay) {
            super(1);
            this.authTimeoutDelay = authTimeoutDelay;
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (unit.toMillis(delay) == authTimeoutDelay.toMillis()) {
                latestAuthTimeout.set(command);
            }
            return super.schedule(command, delay, unit);
        }
    }

    private static final class FakeWebSocket implements WebSocket {

        private final boolean failSend;
        private final AtomicBoolean failNextCommandAck = new AtomicBoolean();
        private final List<String> sentPayloads = new CopyOnWriteArrayList<>();

        private FakeWebSocket(boolean failSend) {
            this.failSend = failSend;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            String payload = data.toString();
            sentPayloads.add(payload);
            if (failSend || (failNextCommandAck.compareAndSet(true, false)
                    && payload.contains("\"type\":\"COMMAND_ACK\""))) {
                CompletableFuture<WebSocket> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("send failed"));
                return failed;
            }
            return CompletableFuture.completedFuture(this);
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
