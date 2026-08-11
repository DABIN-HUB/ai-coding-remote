package com.wangbin.ai.agent.relay.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.RelaySubjectType;
import com.wangbin.ai.agent.contract.coordination.RelayTicketPayload;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.contract.protocol.AgentProtocol;
import com.wangbin.ai.agent.contract.websocket.*;
import com.wangbin.ai.agent.relay.auth.RelayTicketAuthenticator;
import com.wangbin.ai.agent.relay.backpressure.ConnectionOutboundChannel;
import com.wangbin.ai.agent.relay.backpressure.OutboundMessage;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import com.wangbin.ai.agent.relay.connection.*;
import com.wangbin.ai.agent.relay.presence.RelayPresenceRegistry;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RelayWebSocketHandler implements WebSocketHandler {

    private final ObjectMapper objectMapper;
    private final AgentRelayProperties properties;
    private final RelayTicketAuthenticator ticketAuthenticator;
    private final ConnectionManager connectionManager;
    private final RelayPresenceRegistry presenceRegistry;

    public RelayWebSocketHandler(ObjectMapper objectMapper, AgentRelayProperties properties,
                                 RelayTicketAuthenticator ticketAuthenticator,
                                 ConnectionManager connectionManager,
                                 RelayPresenceRegistry presenceRegistry) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.ticketAuthenticator = ticketAuthenticator;
        this.connectionManager = connectionManager;
        this.presenceRegistry = presenceRegistry;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        AtomicReference<ConnectionContext> contextRef = new AtomicReference<>();
        ConnectionOutboundChannel outboundChannel =
                new ConnectionOutboundChannel(properties.getOutboundQueueCapacity());
        Mono<Void> outbound = session.send(outboundChannel.messages()
                .map(outboundMessage -> session.textMessage(outboundMessage.payload())));
        Mono<Void> helloTimeout = Mono.delay(properties.getHelloTimeout())
                .flatMap(ignored -> {
                    if (contextRef.get() == null) {
                        outboundChannel.complete();
                        return session.close(CloseStatus.POLICY_VIOLATION);
                    }
                    return Mono.empty();
                });
        Mono<Void> inbound = session.receive()
                .concatMap(message -> handleMessage(session, message, contextRef, outboundChannel))
                .then();
        Mono<Void> lifecycle = inbound
                .mergeWith(helloTimeout)
                .mergeWith(heartbeat(contextRef).takeUntilOther(session.closeStatus()))
                .then()
                .doFinally(signal -> outboundChannel.complete());
        return Mono.when(outbound, lifecycle)
                .doFinally(signal -> cleanup(contextRef.get()).subscribe());
    }

    private Mono<Void> handleMessage(WebSocketSession session, WebSocketMessage message,
                                     AtomicReference<ConnectionContext> contextRef,
                                     ConnectionOutboundChannel outboundChannel) {
        if (contextRef.get() == null) {
            return authenticate(session, message, contextRef, outboundChannel);
        }
        return parseEnvelope(message.getPayloadAsText(), Object.class)
                .flatMap(envelope -> {
                    ConnectionContext context = contextRef.get();
                    if (envelope.type() == WsMessageType.PONG) {
                        context.markPong();
                        return presenceRegistry.refresh(context.descriptor(), properties.getNodeId());
                    }
                    if (envelope.type() == WsMessageType.PING) {
                        return enqueueCritical(context, WsEnvelope.of(WsMessageType.PONG,
                                new PongPayload(null, Instant.now())));
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> authenticate(WebSocketSession session, WebSocketMessage message,
                                    AtomicReference<ConnectionContext> contextRef,
                                    ConnectionOutboundChannel outboundChannel) {
        return parseHelloEnvelope(message.getPayloadAsText())
                .flatMap(this::validateHello)
                .flatMap(envelope -> ticketAuthenticator.consume(envelope.payload().relayTicket()))
                .switchIfEmpty(Mono.error(new IllegalStateException("relay ticket is invalid or consumed")))
                .flatMap(ticket -> registerAuthenticated(session, ticket, contextRef, outboundChannel));
    }

    private Mono<Void> registerAuthenticated(WebSocketSession session, RelayTicketPayload ticket,
                                             AtomicReference<ConnectionContext> contextRef,
                                             ConnectionOutboundChannel outboundChannel) {
        validateTicketSubject(ticket);
        String connectionId = UUID.randomUUID().toString();
        ConnectionRole role = ticket.subjectType() == RelaySubjectType.DEVICE ? ConnectionRole.DEVICE : ConnectionRole.USER;
        ConnectionDescriptor descriptor = new ConnectionDescriptor(connectionId, role, ticket.tenantId(),
                ticket.userId(), ticket.deviceId(), Instant.now());
        return connectionManager.register(new ConnectionRegistration(descriptor, session, outboundChannel))
                .then(Mono.fromCallable(() -> connectionManager.findByConnectionId(connectionId).orElseThrow()))
                .flatMap(context -> {
                    context.markAuthenticated();
                    context.markPong();
                    contextRef.set(context);
                    return presenceRegistry.register(descriptor, properties.getNodeId())
                            .then(enqueueCritical(context, WsEnvelope.of(WsMessageType.WELCOME,
                                    new WelcomePayload(connectionId, properties.getNodeId(),
                                            properties.getHeartbeatInterval(), Instant.now()))));
                });
    }

    private Flux<Void> heartbeat(AtomicReference<ConnectionContext> contextRef) {
        return Flux.interval(properties.getHeartbeatInterval())
                .flatMap(ignored -> {
                    ConnectionContext context = contextRef.get();
                    if (context == null || context.authState() != ConnectionAuthState.AUTHENTICATED) {
                        return Mono.empty();
                    }
                    if (Instant.now().minus(properties.getHeartbeatTimeout()).isAfter(context.lastPongAt())) {
                        context.markClosed();
                        return cleanup(context).then(context.session().close());
                    }
                    return enqueueCritical(context, WsEnvelope.of(WsMessageType.PING,
                            new PingPayload(UUID.randomUUID().toString(), Instant.now())));
                });
    }

    private Mono<Void> enqueueCritical(ConnectionContext context, WsEnvelope<?> envelope) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(envelope))
                .flatMap(payload -> {
                    boolean accepted = context.enqueue(new OutboundMessage(
                            context.descriptor().connectionId(), EventPriority.CRITICAL, payload, null));
                    if (!accepted) {
                        context.markClosed();
                        return context.session().close(CloseStatus.SERVER_ERROR)
                                .then(Mono.error(new IllegalStateException("critical control message rejected")));
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> cleanup(ConnectionContext context) {
        if (context == null) {
            return Mono.empty();
        }
        context.markClosed();
        return presenceRegistry.unregister(context.descriptor())
                .then(connectionManager.unregister(context.descriptor().connectionId()));
    }

    private <T> Mono<WsEnvelope<T>> parseEnvelope(String json, Class<T> payloadType) {
        return Mono.fromCallable(() -> {
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametricType(WsEnvelope.class, payloadType);
            return objectMapper.readValue(json, type);
        });
    }

    private Mono<WsEnvelope<HelloPayload>> parseHelloEnvelope(String json) {
        return Mono.fromCallable(() -> {
            JsonNode root = objectMapper.readTree(json);
            if (!root.hasNonNull("protocolVersion")
                    || !root.path("payload").hasNonNull("protocolVersion")) {
                throw new IllegalStateException("HELLO protocolVersion is required");
            }
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametricType(WsEnvelope.class, HelloPayload.class);
            return objectMapper.readValue(json, type);
        });
    }

    private Mono<WsEnvelope<HelloPayload>> validateHello(WsEnvelope<HelloPayload> envelope) {
        if (envelope.type() != WsMessageType.HELLO || envelope.payload() == null) {
            return Mono.error(new IllegalStateException("first WebSocket message must be HELLO"));
        }
        HelloPayload payload = envelope.payload();
        if (!AgentProtocol.VERSION.equals(envelope.protocolVersion())
                || !AgentProtocol.VERSION.equals(payload.protocolVersion())
                || !envelope.protocolVersion().equals(payload.protocolVersion())) {
            return Mono.error(new IllegalStateException("WebSocket protocolVersion is incompatible"));
        }
        if (payload.relayTicket() == null || payload.relayTicket().isBlank()) {
            return Mono.error(new IllegalStateException("relay ticket is required"));
        }
        return Mono.just(envelope);
    }

    private void validateTicketSubject(RelayTicketPayload ticket) {
        if (ticket.subjectType() == null) {
            throw new IllegalStateException("relay ticket subjectType is required");
        }
        if (ticket.subjectType() == RelaySubjectType.DEVICE
                && (ticket.deviceId() == null || ticket.deviceId().isBlank())) {
            throw new IllegalStateException("device relay ticket requires deviceId");
        }
        if (ticket.subjectType() == RelaySubjectType.USER && ticket.userId() == null) {
            throw new IllegalStateException("user relay ticket requires userId");
        }
    }
}
