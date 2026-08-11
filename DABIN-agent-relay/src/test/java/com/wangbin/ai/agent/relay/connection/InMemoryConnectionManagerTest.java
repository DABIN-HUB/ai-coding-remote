package com.wangbin.ai.agent.relay.connection;

import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
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

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryConnectionManagerTest {

    @Test
    void deviceConnectionReplacementClosesOldConnectionAndKeepsNewRoute() {
        InMemoryConnectionManager manager = new InMemoryConnectionManager(new AgentRelayProperties());
        WebSocketSession firstSession = mockSession();
        WebSocketSession secondSession = mockSession();

        manager.register(new ConnectionRegistration(descriptor("conn-1"), firstSession)).block();
        manager.register(new ConnectionRegistration(descriptor("conn-2"), secondSession)).block();

        assertThat(manager.findByConnectionId("conn-1")).isEmpty();
        assertThat(manager.findDeviceConnection("dev-1")).map(context -> context.descriptor().connectionId())
                .contains("conn-2");
    }

    @Test
    void everyConnectionHasIsolatedOutboundQueue() {
        InMemoryConnectionManager manager = new InMemoryConnectionManager(new AgentRelayProperties());

        manager.register(new ConnectionRegistration(descriptor("conn-a"), mockSession())).block();
        manager.register(new ConnectionRegistration(new ConnectionDescriptor("conn-b", ConnectionRole.USER,
                1L, 11L, null, Instant.now()), mockSession())).block();

        ConnectionContext a = manager.findByConnectionId("conn-a").orElseThrow();
        ConnectionContext b = manager.findByConnectionId("conn-b").orElseThrow();
        assertThat(a.outboundQueue()).isNotSameAs(b.outboundQueue());
    }

    private ConnectionDescriptor descriptor(String connectionId) {
        return new ConnectionDescriptor(connectionId, ConnectionRole.DEVICE, 1L, 11L, "dev-1", Instant.now());
    }

    private WebSocketSession mockSession() {
        return new TestWebSocketSession();
    }

    private static final class TestWebSocketSession implements WebSocketSession {

        private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();

        @Override
        public String getId() {
            return "test-session";
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
            return Flux.empty();
        }

        @Override
        public Mono<Void> send(Publisher<WebSocketMessage> messages) {
            return Mono.empty();
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
            return Mono.empty();
        }

        @Override
        public WebSocketMessage textMessage(String payload) {
            return new WebSocketMessage(WebSocketMessage.Type.TEXT,
                    bufferFactory.wrap(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
    }
}
