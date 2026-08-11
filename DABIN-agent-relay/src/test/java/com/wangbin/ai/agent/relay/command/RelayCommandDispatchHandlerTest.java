package com.wangbin.ai.agent.relay.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.command.AgentCommand;
import com.wangbin.ai.agent.contract.command.PromptCommandPayload;
import com.wangbin.ai.agent.contract.coordination.RelayCommandDispatchPayload;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.relay.backpressure.OutboundMessage;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import com.wangbin.ai.agent.relay.connection.*;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RelayCommandDispatchHandlerTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long OTHER_TENANT_ID = 2L;
    private static final Long TEST_USER_ID = 11L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_CONNECTION_ID = "conn-1";
    private static final String OTHER_CONNECTION_ID = "conn-2";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_COMMAND_ID = "cmd-1";
    private static final String TEST_PROMPT = "run";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration NO_MESSAGE_TIMEOUT = Duration.ofMillis(50);

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void dispatchesCommandOnlyToMatchingTenantDeviceConnection() {
        InMemoryConnectionManager manager = new InMemoryConnectionManager(new AgentRelayProperties());
        manager.register(new ConnectionRegistration(deviceDescriptor(TEST_CONNECTION_ID, TEST_TENANT_ID),
                fakeSession())).block(BLOCK_TIMEOUT);
        RelayCommandDispatchHandler handler = new RelayCommandDispatchHandler(manager, objectMapper);

        handler.dispatch(dispatchPayload(TEST_TENANT_ID, TEST_CONNECTION_ID)).block(BLOCK_TIMEOUT);

        OutboundMessage message = manager.findByConnectionId(TEST_CONNECTION_ID).orElseThrow()
                .outboundMessages().next().block(BLOCK_TIMEOUT);
        assertThat(message.payload()).contains("\"type\":\"AGENT_COMMAND\"");
        assertThat(message.payload()).contains(TEST_COMMAND_ID);
    }

    @Test
    void ignoresDispatchWhenRouteIdentityDoesNotMatchCurrentConnection() {
        InMemoryConnectionManager manager = new InMemoryConnectionManager(new AgentRelayProperties());
        manager.register(new ConnectionRegistration(deviceDescriptor(TEST_CONNECTION_ID, TEST_TENANT_ID),
                fakeSession())).block(BLOCK_TIMEOUT);
        RelayCommandDispatchHandler handler = new RelayCommandDispatchHandler(manager, objectMapper);

        handler.dispatch(dispatchPayload(OTHER_TENANT_ID, TEST_CONNECTION_ID)).block(BLOCK_TIMEOUT);

        Optional<OutboundMessage> message = manager.findByConnectionId(TEST_CONNECTION_ID).orElseThrow()
                .outboundMessages().next()
                .timeout(NO_MESSAGE_TIMEOUT)
                .onErrorResume(TimeoutException.class, ignored -> Mono.empty())
                .blockOptional();
        assertThat(message).isEmpty();
    }

    private RelayCommandDispatchPayload dispatchPayload(Long tenantId, String connectionId) {
        return new RelayCommandDispatchPayload("relay-1", TEST_DEVICE_ID, connectionId, tenantId,
                new AgentCommand(TEST_COMMAND_ID, TEST_COMMAND_ID, tenantId, TEST_USER_ID, TEST_DEVICE_ID,
                        TEST_PROJECT_ID, TEST_SESSION_ID, AgentType.CODEX, CommandType.PROMPT,
                        new PromptCommandPayload(TEST_PROMPT, Map.of()), Instant.now(),
                        Instant.now().plusSeconds(30), Map.of()), Instant.now());
    }

    private ConnectionDescriptor deviceDescriptor(String connectionId, Long tenantId) {
        return new ConnectionDescriptor(connectionId, ConnectionRole.DEVICE, tenantId, TEST_USER_ID,
                TEST_DEVICE_ID, Instant.now());
    }

    private WebSocketSession fakeSession() {
        return new TestWebSocketSession();
    }

    private static final class TestWebSocketSession implements WebSocketSession {

        private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();

        @Override
        public String getId() {
            return OTHER_CONNECTION_ID;
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
