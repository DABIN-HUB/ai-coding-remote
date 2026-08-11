package com.wangbin.ai.agent.daemon.cloud.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RelayWebSocketClientTest {

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
        WebSocket.Listener listener = connector.listener.get();
        FakeWebSocket socket = connector.socket.get();

        listener.onError(socket, new RuntimeException("network"));
        listener.onClose(socket, 1006, "closed");

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

        waitUntil(() -> controlPlaneClient.ticketCount.get() >= 2);
        assertThat(connector.connectCount.get()).isGreaterThanOrEqualTo(2);
        client.stop();
    }

    @Test
    void welcomeTimeoutReconnectsAndWelcomeCancelsAuthTimeout() throws Exception {
        AgentDaemonProperties properties = properties();
        properties.getCloud().setWelcomeTimeout(Duration.ofMillis(120));
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();
        FakeConnector connector = new FakeConnector(false);
        RelayWebSocketClient client = new RelayWebSocketClient(objectMapper, properties,
                controlPlaneClient, scheduler, connector);

        client.start(credential());
        waitUntil(() -> controlPlaneClient.ticketCount.get() >= 2);
        WebSocket.Listener listener = connector.listener.get();
        FakeWebSocket socket = connector.socket.get();
        String welcome = objectMapper.writeValueAsString(WsEnvelope.of(WsMessageType.WELCOME,
                new WelcomePayload("conn-1", "relay-1", Duration.ofSeconds(20), Instant.now())));

        listener.onText(socket, welcome, true);
        waitUntil(() -> client.state() == RelayConnectionState.CONNECTED);
        int ticketCountAfterWelcome = controlPlaneClient.ticketCount.get();
        sleep(properties.getCloud().getWelcomeTimeout().multipliedBy(2));

        assertThat(controlPlaneClient.ticketCount.get()).isEqualTo(ticketCountAfterWelcome);
        client.stop();
    }

    private AgentDaemonProperties properties() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.getCloud().setReconnectInitialDelay(Duration.ofMillis(20));
        properties.getCloud().setReconnectMaxDelay(Duration.ofMillis(20));
        properties.getCloud().setWelcomeTimeout(Duration.ofMillis(30));
        return properties;
    }

    private DeviceCredentialState credential() {
        DeviceCredentialState credential = new DeviceCredentialState();
        credential.setTenantId(1L);
        credential.setDeviceId("dev-1");
        credential.setCredentialId("cred-1");
        credential.setCredentialSecret("secret");
        credential.setRelayUrl("ws://127.0.0.1:48180/agent/ws");
        return credential;
    }

    private void waitUntil(BooleanCondition condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.matches()) {
                return;
            }
            sleep(Duration.ofMillis(10));
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
            return new RelayTicketResponse("ticket-" + count, Instant.now().plusSeconds(60));
        }
    }

    private static final class FakeConnector implements RelayWebSocketConnector {

        private final boolean failSend;
        private final AtomicInteger connectCount = new AtomicInteger();
        private final AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
        private final AtomicReference<FakeWebSocket> socket = new AtomicReference<>();

        private FakeConnector(boolean failSend) {
            this.failSend = failSend;
        }

        @Override
        public CompletableFuture<WebSocket> connect(URI relayUri, WebSocket.Listener webSocketListener) {
            connectCount.incrementAndGet();
            FakeWebSocket fakeWebSocket = new FakeWebSocket(failSend);
            listener.set(webSocketListener);
            socket.set(fakeWebSocket);
            webSocketListener.onOpen(fakeWebSocket);
            return CompletableFuture.completedFuture(fakeWebSocket);
        }
    }

    private static final class FakeWebSocket implements WebSocket {

        private final boolean failSend;

        private FakeWebSocket(boolean failSend) {
            this.failSend = failSend;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            if (failSend) {
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
