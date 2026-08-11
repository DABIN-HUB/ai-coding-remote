package com.wangbin.ai.agent.daemon.cloud.relay;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.websocket.HelloPayload;
import com.wangbin.ai.agent.contract.websocket.PingPayload;
import com.wangbin.ai.agent.contract.websocket.PongPayload;
import com.wangbin.ai.agent.contract.websocket.WelcomePayload;
import com.wangbin.ai.agent.contract.websocket.WsEnvelope;
import com.wangbin.ai.agent.contract.websocket.WsMessageType;
import com.wangbin.ai.agent.daemon.cloud.controlplane.ControlPlaneClient;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RelayTicketResponse;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.exception.AgentConnectionException;
import com.wangbin.ai.agent.daemon.exception.AgentProtocolException;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maintains the daemon outbound WebSocket to Relay. Every reconnect obtains a new
 * one-time Relay Ticket from Control Plane, because Relay tickets are consumed
 * during HELLO authentication and must never be cached.
 */
@Component
public class RelayWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(RelayWebSocketClient.class);

    private final ObjectMapper objectMapper;
    private final AgentDaemonProperties properties;
    private final ControlPlaneClient controlPlaneClient;
    private final ScheduledExecutorService cloudScheduler;
    private final HttpClient httpClient;
    private final AtomicReference<RelayConnectionState> state =
            new AtomicReference<>(RelayConnectionState.DISCONNECTED);
    private final AtomicBoolean reconnectLoopActive = new AtomicBoolean(false);
    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile int reconnectAttempt;

    public RelayWebSocketClient(ObjectMapper objectMapper,
                                AgentDaemonProperties properties,
                                ControlPlaneClient controlPlaneClient,
                                @Qualifier("agentCloudScheduler") ScheduledExecutorService cloudScheduler) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.controlPlaneClient = controlPlaneClient;
        this.cloudScheduler = cloudScheduler;
        this.httpClient = HttpClient.newHttpClient();
    }

    public RelayConnectionState state() {
        return state.get();
    }

    public void start(DeviceCredentialState credential) {
        Objects.requireNonNull(credential, "credential must not be null");
        if (!reconnectLoopActive.compareAndSet(false, true)) {
            log.debug("relay reconnect loop already active: deviceId={}", credential.getDeviceId());
            return;
        }
        state.set(RelayConnectionState.DISCONNECTED);
        scheduleConnect(credential, Duration.ZERO);
    }

    public void stop() {
        reconnectLoopActive.set(false);
        state.set(RelayConnectionState.STOPPED);
        ScheduledFuture<?> future = reconnectFuture;
        if (future != null) {
            future.cancel(false);
        }
        WebSocket current = webSocket;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "daemon stopped");
        }
    }

    private void scheduleConnect(DeviceCredentialState credential, Duration delay) {
        if (!reconnectLoopActive.get()) {
            return;
        }
        reconnectFuture = cloudScheduler.schedule(() -> connectOnce(credential),
                Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    private void connectOnce(DeviceCredentialState credential) {
        if (!reconnectLoopActive.get()) {
            return;
        }
        try {
            state.set(RelayConnectionState.CONNECTING);
            RelayTicketResponse ticket = controlPlaneClient.createDeviceRelayTicket(credential);
            state.set(RelayConnectionState.AUTHENTICATING);
            RelayListener listener = new RelayListener(credential);
            httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(credential.getRelayUrl()), listener)
                    .whenComplete((socket, throwable) -> {
                        if (throwable != null) {
                            handleDisconnect(credential, throwable);
                            return;
                        }
                        webSocket = socket;
                        listener.attach(socket);
                        send(socket, WsEnvelope.of(WsMessageType.HELLO,
                                new HelloPayload(com.wangbin.ai.agent.contract.protocol.AgentProtocol.VERSION,
                                        ticket.ticket())));
                    });
        } catch (RuntimeException ex) {
            handleDisconnect(credential, ex);
        }
    }

    private void handleDisconnect(DeviceCredentialState credential, Throwable throwable) {
        if (!reconnectLoopActive.get()) {
            return;
        }
        state.set(RelayConnectionState.RECONNECT_WAIT);
        Duration delay = nextReconnectDelay();
        log.warn("relay connection disconnected: deviceId={}, state={}, retryDelay={}, reason={}",
                credential.getDeviceId(), state.get(), delay, throwable.getClass().getSimpleName());
        scheduleConnect(credential, delay);
    }

    private Duration nextReconnectDelay() {
        reconnectAttempt++;
        Duration initial = properties.getCloud().getReconnectInitialDelay();
        Duration max = properties.getCloud().getReconnectMaxDelay();
        long baseMillis = initial.toMillis();
        long candidate = baseMillis * (1L << Math.min(reconnectAttempt - 1, 10));
        long capped = Math.min(candidate, max.toMillis());
        long jitter = capped <= 1 ? 0 : ThreadLocalRandom.current().nextLong(capped / 4 + 1);
        return Duration.ofMillis(Math.min(capped + jitter, max.toMillis()));
    }

    private void send(WebSocket socket, WsEnvelope<?> envelope) {
        try {
            socket.sendText(objectMapper.writeValueAsString(envelope), true)
                    .exceptionally(throwable -> {
                        throw new AgentConnectionException("failed to send relay WebSocket message", throwable);
                    });
        } catch (Exception ex) {
            throw new AgentProtocolException("failed to serialize relay WebSocket message", ex);
        }
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    private final class RelayListener implements WebSocket.Listener {

        private final DeviceCredentialState credential;
        private final StringBuilder textBuffer = new StringBuilder();
        private WebSocket socket;

        private RelayListener(DeviceCredentialState credential) {
            this.credential = credential;
        }

        private void attach(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (!last) {
                webSocket.request(1);
                return null;
            }
            String text = textBuffer.toString();
            textBuffer.setLength(0);
            handleEnvelope(text);
            webSocket.request(1);
            return null;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            RelayConnectionState previous = state.get();
            if (previous != RelayConnectionState.STOPPED) {
                handleDisconnect(credential, new AgentConnectionException(
                        "relay WebSocket closed with status " + statusCode, null));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handleDisconnect(credential, error);
        }

        private void handleEnvelope(String json) {
            try {
                WsEnvelope<?> envelope = objectMapper.readValue(json, envelopeType(Object.class));
                if (envelope.type() == WsMessageType.WELCOME) {
                    WsEnvelope<WelcomePayload> welcome = objectMapper.readValue(json, envelopeType(WelcomePayload.class));
                    reconnectAttempt = 0;
                    state.set(RelayConnectionState.CONNECTED);
                    log.info("relay authenticated: deviceId={}, connectionId={}, relayNodeId={}, heartbeatInterval={}",
                            credential.getDeviceId(), welcome.payload().connectionId(),
                            welcome.payload().relayNodeId(), welcome.payload().heartbeatInterval());
                    return;
                }
                if (envelope.type() == WsMessageType.PING) {
                    WsEnvelope<PingPayload> ping = objectMapper.readValue(json, envelopeType(PingPayload.class));
                    send(socket, WsEnvelope.of(WsMessageType.PONG,
                            new PongPayload(ping.payload() == null ? null : ping.payload().pingId(), Instant.now())));
                    return;
                }
                if (envelope.type() == WsMessageType.PONG) {
                    return;
                }
                log.debug("ignored relay message before AgentEvent uplink is enabled: type={}", envelope.type());
            } catch (Exception ex) {
                throw new AgentProtocolException("failed to parse relay WebSocket message", ex);
            }
        }

        private <T> JavaType envelopeType(Class<T> payloadType) {
            return objectMapper.getTypeFactory().constructParametricType(WsEnvelope.class, payloadType);
        }
    }
}
