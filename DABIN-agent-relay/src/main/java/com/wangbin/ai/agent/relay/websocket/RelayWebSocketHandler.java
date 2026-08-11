package com.wangbin.ai.agent.relay.websocket;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.RelaySubjectType;
import com.wangbin.ai.agent.contract.coordination.RelayTicketPayload;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.contract.websocket.*;
import com.wangbin.ai.agent.relay.auth.RelayTicketAuthenticator;
import com.wangbin.ai.agent.relay.backpressure.OutboundMessage;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import com.wangbin.ai.agent.relay.connection.*;
import com.wangbin.ai.agent.relay.presence.RelayPresenceRegistry;
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
        Mono<Void> helloTimeout = Mono.delay(properties.getHelloTimeout())
                .flatMap(ignored -> {
                    if (contextRef.get() == null) {
                        return session.close();
                    }
                    return Mono.empty();
                });
        return session.receive()
                .concatMap(message -> handleMessage(session, message, contextRef))
                .mergeWith(helloTimeout)
                .mergeWith(heartbeat(contextRef).takeUntilOther(session.closeStatus()))
                .then()
                .doFinally(signal -> cleanup(contextRef.get()).subscribe());
    }

    private Mono<Void> handleMessage(WebSocketSession session, WebSocketMessage message,
                                     AtomicReference<ConnectionContext> contextRef) {
        if (contextRef.get() == null) {
            return authenticate(session, message, contextRef);
        }
        return parseEnvelope(message.getPayloadAsText(), Object.class)
                .flatMap(envelope -> {
                    ConnectionContext context = contextRef.get();
                    if (envelope.type() == WsMessageType.PONG) {
                        context.markPong();
                        return presenceRegistry.refresh(context.descriptor(), properties.getNodeId());
                    }
                    if (envelope.type() == WsMessageType.PING) {
                        return send(context, WsEnvelope.of(WsMessageType.PONG,
                                new PongPayload(null, Instant.now())));
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> authenticate(WebSocketSession session, WebSocketMessage message,
                                    AtomicReference<ConnectionContext> contextRef) {
        return parseEnvelope(message.getPayloadAsText(), HelloPayload.class)
                .filter(envelope -> envelope.type() == WsMessageType.HELLO && envelope.payload() != null)
                .switchIfEmpty(Mono.error(new IllegalStateException("first WebSocket message must be HELLO")))
                .flatMap(envelope -> ticketAuthenticator.consume(envelope.payload().relayTicket()))
                .switchIfEmpty(Mono.error(new IllegalStateException("relay ticket is invalid or consumed")))
                .flatMap(ticket -> registerAuthenticated(session, ticket, contextRef));
    }

    private Mono<Void> registerAuthenticated(WebSocketSession session, RelayTicketPayload ticket,
                                             AtomicReference<ConnectionContext> contextRef) {
        String connectionId = UUID.randomUUID().toString();
        ConnectionRole role = ticket.subjectType() == RelaySubjectType.DEVICE ? ConnectionRole.DEVICE : ConnectionRole.USER;
        ConnectionDescriptor descriptor = new ConnectionDescriptor(connectionId, role, ticket.tenantId(),
                ticket.userId(), ticket.deviceId(), Instant.now());
        return connectionManager.register(new ConnectionRegistration(descriptor, session))
                .then(Mono.fromCallable(() -> connectionManager.findByConnectionId(connectionId).orElseThrow()))
                .flatMap(context -> {
                    context.markAuthenticated();
                    context.markPong();
                    contextRef.set(context);
                    return presenceRegistry.register(descriptor, properties.getNodeId())
                            .then(send(context, WsEnvelope.of(WsMessageType.WELCOME,
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
                    return send(context, WsEnvelope.of(WsMessageType.PING,
                            new PingPayload(UUID.randomUUID().toString(), Instant.now())));
                });
    }

    private Mono<Void> send(ConnectionContext context, WsEnvelope<?> envelope) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(envelope))
                .flatMap(payload -> {
                    boolean accepted = context.outboundQueue().offer(new OutboundMessage(
                            context.descriptor().connectionId(), EventPriority.CRITICAL, payload, null));
                    if (!accepted) {
                        return Mono.error(new IllegalStateException("critical control message rejected"));
                    }
                    return context.session().send(Flux.fromIterable(context.drainAll())
                            .map(outbound -> context.session().textMessage(outbound.payload())));
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
}
