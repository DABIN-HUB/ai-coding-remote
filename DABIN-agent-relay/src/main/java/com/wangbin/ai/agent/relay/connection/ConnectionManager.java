package com.wangbin.ai.agent.relay.connection;

import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.Set;

public interface ConnectionManager {

    Mono<Void> register(ConnectionRegistration registration);

    Mono<Void> unregister(String connectionId);

    Optional<ConnectionContext> findByConnectionId(String connectionId);

    Optional<ConnectionContext> findDeviceConnection(String deviceId);

    Set<ConnectionContext> findUserConnections(Long userId);

}
