package com.wangbin.ai.agent.daemon.cloud.relay;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.command.AgentCommand;
import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.protocol.AgentProtocol;
import com.wangbin.ai.agent.contract.websocket.HelloPayload;
import com.wangbin.ai.agent.contract.websocket.PingPayload;
import com.wangbin.ai.agent.contract.websocket.PongPayload;
import com.wangbin.ai.agent.contract.websocket.WelcomePayload;
import com.wangbin.ai.agent.contract.websocket.WsEnvelope;
import com.wangbin.ai.agent.contract.websocket.WsMessageType;
import com.wangbin.ai.agent.daemon.cloud.controlplane.ControlPlaneClient;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RelayTicketResponse;
import com.wangbin.ai.agent.daemon.command.AgentCommandHandler;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maintains the daemon outbound WebSocket to Relay. Disconnect callbacks from
 * old sockets are isolated by generation id, so a stale close cannot disturb a
 * newer authenticated connection.
 */
@Component
public class RelayWebSocketClient implements DaemonOutboundSender {

    private static final Logger log = LoggerFactory.getLogger(RelayWebSocketClient.class);

    private final ObjectMapper objectMapper;
    private final AgentDaemonProperties properties;
    private final ControlPlaneClient controlPlaneClient;
    private final ScheduledExecutorService cloudScheduler;
    private final RelayWebSocketConnector connector;
    private final AgentCommandHandler commandHandler;
    private final DaemonOutboundChannel outboundChannel;
    private final AtomicReference<RelayConnectionState> state =
            new AtomicReference<>(RelayConnectionState.DISCONNECTED);
    private final AtomicLong generation = new AtomicLong();
    private final Object lifecycleMonitor = new Object();
    private volatile boolean running;
    private volatile boolean connectInFlight;
    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile ScheduledFuture<?> authTimeoutFuture;
    private volatile int reconnectAttempt;
    private final AtomicLong attemptSequence = new AtomicLong();
    private volatile long activeAttemptId;
    private volatile DeviceCredentialState activeCredential;

    public RelayWebSocketClient(ObjectMapper objectMapper,
                                AgentDaemonProperties properties,
                                ControlPlaneClient controlPlaneClient,
                                @Qualifier("agentCloudScheduler") ScheduledExecutorService cloudScheduler,
                                AgentCommandHandler commandHandler,
                                DaemonOutboundChannel outboundChannel) {
        this(objectMapper, properties, controlPlaneClient, cloudScheduler, commandHandler, outboundChannel,
                (relayUri, listener) -> HttpClient.newHttpClient()
                        .newWebSocketBuilder()
                        .buildAsync(relayUri, listener));
    }

    RelayWebSocketClient(ObjectMapper objectMapper,
                         AgentDaemonProperties properties,
                         ControlPlaneClient controlPlaneClient,
                         ScheduledExecutorService cloudScheduler,
                         RelayWebSocketConnector connector) {
        this(objectMapper, properties, controlPlaneClient, cloudScheduler,
                (command, credential, outboundSender) -> {
                }, new DaemonOutboundChannel(objectMapper, properties), connector);
    }

    RelayWebSocketClient(ObjectMapper objectMapper,
                         AgentDaemonProperties properties,
                         ControlPlaneClient controlPlaneClient,
                         ScheduledExecutorService cloudScheduler,
                         AgentCommandHandler commandHandler,
                         DaemonOutboundChannel outboundChannel,
                         RelayWebSocketConnector connector) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.controlPlaneClient = controlPlaneClient;
        this.cloudScheduler = cloudScheduler;
        this.commandHandler = commandHandler;
        this.outboundChannel = outboundChannel;
        this.connector = connector;
    }

    public RelayConnectionState state() {
        return state.get();
    }

    public void start(DeviceCredentialState credential) {
        Objects.requireNonNull(credential, "credential must not be null");
        long currentGeneration;
        synchronized (lifecycleMonitor) {
            if (running) {
                log.debug("relay reconnect loop already active: deviceId={}", credential.getDeviceId());
                return;
            }
            running = true;
            connectInFlight = false;
            reconnectAttempt = 0;
            activeAttemptId = 0;
            state.set(RelayConnectionState.DISCONNECTED);
            activeCredential = credential;
            currentGeneration = generation.incrementAndGet();
            scheduleConnectLocked(credential, currentGeneration, Duration.ZERO);
        }
    }

    public void stop() {
        WebSocket socketToClose;
        synchronized (lifecycleMonitor) {
            running = false;
            generation.incrementAndGet();
            state.set(RelayConnectionState.STOPPED);
            connectInFlight = false;
            reconnectAttempt = 0;
            activeAttemptId = 0;
            activeCredential = null;
            cancelFuture(reconnectFuture);
            cancelFuture(authTimeoutFuture);
            reconnectFuture = null;
            authTimeoutFuture = null;
            socketToClose = webSocket;
            webSocket = null;
        }
        if (socketToClose != null) {
            socketToClose.sendClose(WebSocket.NORMAL_CLOSURE, "daemon stopped");
        }
    }

    private void scheduleConnectLocked(DeviceCredentialState credential, long expectedGeneration, Duration delay) {
        if (!running || expectedGeneration != generation.get()) {
            return;
        }
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            return;
        }
        if (!delay.isZero()) {
            state.set(RelayConnectionState.RECONNECT_WAIT);
        }
        reconnectFuture = cloudScheduler.schedule(() -> connectOnce(credential, expectedGeneration),
                Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    private void connectOnce(DeviceCredentialState credential, long expectedGeneration) {
        long attemptId;
        synchronized (lifecycleMonitor) {
            if (!running || expectedGeneration != generation.get() || connectInFlight) {
                return;
            }
            reconnectFuture = null;
            connectInFlight = true;
            attemptId = attemptSequence.incrementAndGet();
            activeAttemptId = attemptId;
            state.set(RelayConnectionState.CONNECTING);
        }
        try {
            RelayTicketResponse ticket = controlPlaneClient.createDeviceRelayTicket(credential);
            synchronized (lifecycleMonitor) {
                if (!isCurrentAttemptLocked(expectedGeneration, attemptId)) {
                    return;
                }
                state.set(RelayConnectionState.AUTHENTICATING);
            }
            RelayListener listener = new RelayListener(credential, expectedGeneration, attemptId);
            connector.connect(URI.create(credential.getRelayUrl()), listener)
                    .whenComplete((socket, throwable) -> onConnectResult(credential, expectedGeneration, attemptId,
                            listener, ticket, socket, throwable));
        } catch (RuntimeException ex) {
            handleDisconnect(credential, expectedGeneration, attemptId, null, ex);
        }
    }

    private void onConnectResult(DeviceCredentialState credential, long expectedGeneration, long attemptId,
                                 RelayListener listener,
                                 RelayTicketResponse ticket, WebSocket socket, Throwable throwable) {
        if (throwable != null) {
            handleDisconnect(credential, expectedGeneration, attemptId, null, throwable);
            return;
        }
        synchronized (lifecycleMonitor) {
            if (!isCurrentAttemptLocked(expectedGeneration, attemptId)) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "stale relay connection");
                return;
            }
            if (state.get() == RelayConnectionState.CONNECTED && webSocket == socket) {
                connectInFlight = false;
                cancelFuture(authTimeoutFuture);
                authTimeoutFuture = null;
                return;
            }
            webSocket = socket;
            listener.attach(socket);
            scheduleAuthTimeoutLocked(credential, expectedGeneration, attemptId, socket);
        }
        boolean accepted = sendEnvelope(socket, WsEnvelope.of(WsMessageType.HELLO,
                new HelloPayload(AgentProtocol.VERSION, ticket.ticket())),
                () -> handleDisconnect(credential, expectedGeneration, attemptId, socket,
                        new AgentConnectionException("failed to send HELLO", null)));
        if (!accepted) {
            handleDisconnect(credential, expectedGeneration, attemptId, socket,
                    new AgentConnectionException("daemon outbound queue rejected HELLO", null));
        }
    }

    private void scheduleAuthTimeoutLocked(DeviceCredentialState credential, long expectedGeneration, long attemptId,
                                           WebSocket socket) {
        cancelFuture(authTimeoutFuture);
        authTimeoutFuture = cloudScheduler.schedule(() -> handleDisconnect(credential, expectedGeneration, attemptId,
                        socket,
                        new AgentConnectionException("relay WELCOME timeout", null)),
                properties.getCloud().getWelcomeTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void handleDisconnect(DeviceCredentialState credential, long expectedGeneration, long attemptId,
                                  WebSocket source, Throwable throwable) {
        Duration delay;
        WebSocket socketToAbort = null;
        synchronized (lifecycleMonitor) {
            if (!isCurrentAttemptLocked(expectedGeneration, attemptId)) {
                return;
            }
            if (source != null && webSocket != null && source != webSocket) {
                return;
            }
            cancelFuture(authTimeoutFuture);
            authTimeoutFuture = null;
            if (source != null && source == webSocket) {
                socketToAbort = webSocket;
                webSocket = null;
            }
            connectInFlight = false;
            if (reconnectFuture != null && !reconnectFuture.isDone()) {
                return;
            }
            state.set(RelayConnectionState.RECONNECT_WAIT);
            delay = nextReconnectDelay();
            scheduleConnectLocked(credential, expectedGeneration, delay);
        }
        if (socketToAbort != null) {
            socketToAbort.abort();
        }
        log.warn("relay connection disconnected: deviceId={}, state={}, retryDelay={}, reason={}",
                credential.getDeviceId(), state.get(), delay, throwable.getClass().getSimpleName());
    }

    private void onWelcome(DeviceCredentialState credential, long expectedGeneration, long attemptId, WebSocket source,
                           WelcomePayload payload) {
        synchronized (lifecycleMonitor) {
            if (!isCurrentAttemptLocked(expectedGeneration, attemptId)) {
                return;
            }
            if (source == null) {
                return;
            }
            if (webSocket == null) {
                webSocket = source;
            } else if (source != webSocket) {
                return;
            }
            cancelFuture(authTimeoutFuture);
            authTimeoutFuture = null;
            connectInFlight = false;
            reconnectAttempt = 0;
            state.set(RelayConnectionState.CONNECTED);
        }
        log.info("relay authenticated: deviceId={}, connectionId={}, relayNodeId={}, heartbeatInterval={}",
                credential.getDeviceId(), payload.connectionId(), payload.relayNodeId(), payload.heartbeatInterval());
    }

    private boolean isCurrentAttemptLocked(long expectedGeneration, long attemptId) {
        return running && expectedGeneration == generation.get() && attemptId == activeAttemptId;
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

    private boolean sendEnvelope(WebSocket socket, WsEnvelope<?> envelope, Runnable failureHandler) {
        if (socket == null) {
            return false;
        }
        return outboundChannel.enqueue(socket, envelope, failureHandler);
    }

    @Override
    public boolean sendCommandAck(CommandAck ack) {
        WebSocket socket = webSocket;
        DeviceCredentialState credential = activeCredential;
        long expectedGeneration = generation.get();
        long attemptId = activeAttemptId;
        boolean accepted = sendEnvelope(socket, WsEnvelope.of(WsMessageType.COMMAND_ACK, ack),
                () -> handleAttemptSendFailure(credential, expectedGeneration, attemptId, socket,
                        new AgentConnectionException("failed to send CommandAck", null)));
        if (!accepted) {
            handleAttemptSendFailure(credential, expectedGeneration, attemptId, socket,
                    new AgentConnectionException("daemon outbound queue rejected CommandAck", null));
        }
        return accepted;
    }

    @Override
    public boolean sendAgentEvent(AgentEvent event) {
        WebSocket socket = webSocket;
        DeviceCredentialState credential = activeCredential;
        long expectedGeneration = generation.get();
        long attemptId = activeAttemptId;
        boolean accepted = sendEnvelope(socket, WsEnvelope.of(WsMessageType.AGENT_EVENT, event),
                () -> handleAttemptSendFailure(credential, expectedGeneration, attemptId, socket,
                        new AgentConnectionException("failed to send AgentEvent", null)));
        if (!accepted && event != null && (event.priority() == com.wangbin.ai.agent.contract.enums.EventPriority.CRITICAL
                || event.priority() == com.wangbin.ai.agent.contract.enums.EventPriority.IMPORTANT)) {
            handleAttemptSendFailure(credential, expectedGeneration, attemptId, socket,
                    new AgentConnectionException("daemon outbound queue rejected reliable AgentEvent", null));
        }
        return accepted;
    }

    private void handleAttemptSendFailure(DeviceCredentialState credential, long expectedGeneration, long attemptId,
                                          WebSocket socket, Throwable throwable) {
        if (credential == null) {
            return;
        }
        handleDisconnect(credential, expectedGeneration, attemptId, socket, throwable);
    }

    private void cancelFuture(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    private final class RelayListener implements WebSocket.Listener {

        private final DeviceCredentialState credential;
        private final long expectedGeneration;
        private final long attemptId;
        private final StringBuilder textBuffer = new StringBuilder();
        private WebSocket socket;

        private RelayListener(DeviceCredentialState credential, long expectedGeneration, long attemptId) {
            this.credential = credential;
            this.expectedGeneration = expectedGeneration;
            this.attemptId = attemptId;
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
            attach(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handleDisconnect(credential, expectedGeneration, attemptId, webSocket, new AgentConnectionException(
                    "relay WebSocket closed with status " + statusCode, null));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handleDisconnect(credential, expectedGeneration, attemptId, webSocket, error);
        }

        private void handleEnvelope(String json) {
            try {
                WsEnvelope<?> envelope = objectMapper.readValue(json, envelopeType(Object.class));
                if (envelope.type() == WsMessageType.WELCOME) {
                    WsEnvelope<WelcomePayload> welcome = objectMapper.readValue(json, envelopeType(WelcomePayload.class));
                    onWelcome(credential, expectedGeneration, attemptId, socket, welcome.payload());
                    return;
                }
                if (envelope.type() == WsMessageType.PING) {
                    WsEnvelope<PingPayload> ping = objectMapper.readValue(json, envelopeType(PingPayload.class));
                    boolean accepted = sendEnvelope(socket, WsEnvelope.of(WsMessageType.PONG,
                                    new PongPayload(ping.payload() == null ? null : ping.payload().pingId(), Instant.now())),
                            () -> handleDisconnect(credential, expectedGeneration, attemptId, socket,
                                    new AgentConnectionException("failed to send PONG", null)));
                    if (!accepted) {
                        handleDisconnect(credential, expectedGeneration, attemptId, socket,
                                new AgentConnectionException("daemon outbound queue rejected PONG", null));
                    }
                    return;
                }
                if (envelope.type() == WsMessageType.PONG) {
                    return;
                }
                if (envelope.type() == WsMessageType.AGENT_COMMAND) {
                    WsEnvelope<AgentCommand> command = objectMapper.readValue(json, envelopeType(AgentCommand.class));
                    try {
                        commandHandler.handle(command.payload(), credential, RelayWebSocketClient.this);
                    } catch (RuntimeException ex) {
                        log.warn("failed to handle AgentCommand business message: commandId={}",
                                command.payload() == null ? null : command.payload().commandId(), ex);
                    }
                    return;
                }
                log.debug("ignored relay message before AgentEvent uplink is enabled: type={}", envelope.type());
            } catch (Exception ex) {
                handleDisconnect(credential, expectedGeneration, attemptId, socket,
                        new AgentProtocolException("failed to parse relay WebSocket message", ex));
            }
        }

        private <T> JavaType envelopeType(Class<T> payloadType) {
            return objectMapper.getTypeFactory().constructParametricType(WsEnvelope.class, payloadType);
        }
    }
}
