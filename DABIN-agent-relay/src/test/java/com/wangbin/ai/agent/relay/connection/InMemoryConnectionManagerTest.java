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
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryConnectionManagerTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String CONNECTION_FIRST = "conn-1";
    private static final String CONNECTION_SECOND = "conn-2";
    private static final String CONNECTION_A = "conn-a";
    private static final String CONNECTION_B = "conn-b";
    private static final String CONNECTION_PREFIX = "conn-";
    private static final String TEST_SESSION_ID = "test-session";
    private static final int REPLACEMENT_START_INDEX = 1;
    private static final int REPLACEMENT_END_INDEX = 5;

    @Test
    void deviceConnectionReplacementClosesOldConnectionAndKeepsNewRoute() {
        InMemoryConnectionManager manager = new InMemoryConnectionManager(new AgentRelayProperties());
        WebSocketSession firstSession = mockSession();
        WebSocketSession secondSession = mockSession();

        manager.register(new ConnectionRegistration(descriptor(CONNECTION_FIRST), firstSession)).block();
        manager.register(new ConnectionRegistration(descriptor(CONNECTION_SECOND), secondSession)).block();

        assertThat(manager.findByConnectionId(CONNECTION_FIRST)).isEmpty();
        assertThat(manager.findDeviceConnection(TEST_DEVICE_ID)).map(context -> context.descriptor().connectionId())
                .contains(CONNECTION_SECOND);
    }

    @Test
    void everyConnectionHasIsolatedOutboundQueue() {
        InMemoryConnectionManager manager = new InMemoryConnectionManager(new AgentRelayProperties());

        manager.register(new ConnectionRegistration(descriptor(CONNECTION_A), mockSession())).block();
        manager.register(new ConnectionRegistration(new ConnectionDescriptor(CONNECTION_B, ConnectionRole.USER,
                TEST_TENANT_ID, TEST_USER_ID, null, Instant.now()), mockSession())).block();

        ConnectionContext a = manager.findByConnectionId(CONNECTION_A).orElseThrow();
        ConnectionContext b = manager.findByConnectionId(CONNECTION_B).orElseThrow();
        assertThat(a.outboundChannel()).isNotSameAs(b.outboundChannel());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deviceConnectionReplacementDoesNotLeaveStaleUserRouteIds() throws Exception {
        InMemoryConnectionManager manager = new InMemoryConnectionManager(new AgentRelayProperties());

        for (int i = REPLACEMENT_START_INDEX; i <= REPLACEMENT_END_INDEX; i++) {
            manager.register(new ConnectionRegistration(descriptor(CONNECTION_PREFIX + i), mockSession())).block();
        }

        Field field = InMemoryConnectionManager.class.getDeclaredField("userToConnectionIds");
        field.setAccessible(true);
        Map<TenantUserKey, Set<String>> userRoutes = (Map<TenantUserKey, Set<String>>) field.get(manager);
        assertThat(userRoutes.get(new TenantUserKey(TEST_TENANT_ID, TEST_USER_ID)))
                .containsExactly(CONNECTION_PREFIX + REPLACEMENT_END_INDEX);
    }

    private ConnectionDescriptor descriptor(String connectionId) {
        return new ConnectionDescriptor(connectionId, ConnectionRole.DEVICE, TEST_TENANT_ID, TEST_USER_ID,
                TEST_DEVICE_ID, Instant.now());
    }

    private WebSocketSession mockSession() {
        return new TestWebSocketSession();
    }

    private static final class TestWebSocketSession implements WebSocketSession {

        private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();

        @Override
        public String getId() {
            return TEST_SESSION_ID;
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
