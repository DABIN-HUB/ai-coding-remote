package com.wangbin.ai.agent.relay.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.coordination.RelaySubjectType;
import com.wangbin.ai.agent.contract.coordination.RelayTicketPayload;
import com.wangbin.ai.agent.contract.protocol.AgentProtocol;
import com.wangbin.ai.agent.contract.websocket.HelloPayload;
import com.wangbin.ai.agent.contract.websocket.WsEnvelope;
import com.wangbin.ai.agent.contract.websocket.WsMessageType;
import com.wangbin.ai.agent.relay.auth.RelayTicketAuthenticator;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import com.wangbin.ai.agent.relay.connection.ConnectionDescriptor;
import com.wangbin.ai.agent.relay.connection.InMemoryConnectionManager;
import com.wangbin.ai.agent.relay.presence.RelayPresenceRegistry;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayWebSocketHandlerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void helloWithValidDeviceTicketAuthenticatesAndReturnsWelcome() throws Exception {
        AgentRelayProperties properties = properties();
        InMemoryConnectionManager connectionManager = new InMemoryConnectionManager(properties);
        RelayTicketPayload ticket = new RelayTicketPayload("ticket-1", RelaySubjectType.DEVICE, 1L,
                11L, "dev-1", Instant.now(), Instant.now().plusSeconds(60));
        StubPresenceRegistry presenceRegistry = new StubPresenceRegistry(properties);
        RelayWebSocketHandler handler = new RelayWebSocketHandler(objectMapper, properties,
                new StubAuthenticator(ticket), connectionManager, presenceRegistry);
        TestWebSocketSession session = new TestWebSocketSession(hello("ticket-1"));

        handler.handle(session).block(Duration.ofSeconds(1));

        assertThat(presenceRegistry.registered.get()).isNotNull();
        assertThat(presenceRegistry.registered.get().deviceId()).isEqualTo("dev-1");
        assertThat(session.sentPayloads).anySatisfy(payload ->
                assertThat(payload).contains("\"type\":\"WELCOME\""));
    }

    @Test
    void helloWithConsumedTicketIsRejected() throws Exception {
        AgentRelayProperties properties = properties();
        RelayWebSocketHandler handler = new RelayWebSocketHandler(objectMapper, properties,
                new StubAuthenticator(null), new InMemoryConnectionManager(properties),
                new StubPresenceRegistry(properties));
        TestWebSocketSession session = new TestWebSocketSession(hello("consumed-ticket"));

        assertThatThrownBy(() -> handler.handle(session).block(Duration.ofSeconds(1)))
                .hasMessageContaining("relay ticket is invalid or consumed");
    }

    private String hello(String ticket) throws Exception {
        return objectMapper.writeValueAsString(WsEnvelope.of(WsMessageType.HELLO,
                new HelloPayload(AgentProtocol.VERSION, ticket)));
    }

    private AgentRelayProperties properties() {
        AgentRelayProperties properties = new AgentRelayProperties();
        properties.setHelloTimeout(Duration.ofMillis(10));
        properties.setHeartbeatInterval(Duration.ofMillis(50));
        properties.setHeartbeatTimeout(Duration.ofMillis(100));
        return properties;
    }

    private static final class StubAuthenticator extends RelayTicketAuthenticator {

        private final RelayTicketPayload ticket;

        private StubAuthenticator(RelayTicketPayload ticket) {
            super(null, null);
            this.ticket = ticket;
        }

        @Override
        public Mono<RelayTicketPayload> consume(String ticketValue) {
            return ticket == null ? Mono.empty() : Mono.just(ticket);
        }
    }

    private static final class StubPresenceRegistry extends RelayPresenceRegistry {

        private final AtomicReference<ConnectionDescriptor> registered = new AtomicReference<>();

        private StubPresenceRegistry(AgentRelayProperties properties) {
            super(null, null, properties);
        }

        @Override
        public Mono<Void> register(ConnectionDescriptor descriptor, String relayNodeId) {
            registered.set(descriptor);
            return Mono.empty();
        }

        @Override
        public Mono<Void> refresh(ConnectionDescriptor descriptor, String relayNodeId) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> unregister(ConnectionDescriptor descriptor) {
            return Mono.empty();
        }
    }

    private static final class TestWebSocketSession implements WebSocketSession {

        private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        private final Flux<WebSocketMessage> inbound;
        private final List<String> sentPayloads = new CopyOnWriteArrayList<>();

        private TestWebSocketSession(String firstText) {
            this.inbound = Flux.just(text(firstText));
        }

        @Override
        public String getId() {
            return "test-ws";
        }

        @Override
        public HandshakeInfo getHandshakeInfo() {
            return null;
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return bufferFactory;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public Flux<WebSocketMessage> receive() {
            return inbound;
        }

        @Override
        public Mono<Void> send(Publisher<WebSocketMessage> messages) {
            return Flux.from(messages)
                    .doOnNext(message -> sentPayloads.add(message.getPayloadAsText(StandardCharsets.UTF_8)))
                    .then();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public Mono<Void> close(CloseStatus status) {
            return Mono.empty();
        }

        @Override
        public Mono<CloseStatus> closeStatus() {
            return Mono.just(CloseStatus.NORMAL);
        }

        @Override
        public WebSocketMessage textMessage(String payload) {
            return text(payload);
        }

        @Override
        public WebSocketMessage binaryMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(bufferFactory));
        }

        @Override
        public WebSocketMessage pingMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(bufferFactory));
        }

        @Override
        public WebSocketMessage pongMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(bufferFactory));
        }

        private WebSocketMessage text(String payload) {
            return new WebSocketMessage(WebSocketMessage.Type.TEXT,
                    bufferFactory.wrap(payload.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
