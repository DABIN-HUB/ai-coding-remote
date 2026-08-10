package com.wangbin.ai.agent.relay.connection;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class InMemoryConnectionManager implements ConnectionManager {

    private final Map<String, ConnectionRegistration> byConnectionId = new ConcurrentHashMap<>();
    private final Map<String, String> deviceToConnectionId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userToConnectionIds = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> register(ConnectionRegistration registration) {
        return Mono.fromRunnable(() -> {
            ConnectionDescriptor descriptor = registration.descriptor();
            byConnectionId.put(descriptor.connectionId(), registration);
            if (descriptor.deviceId() != null && !descriptor.deviceId().isBlank()) {
                deviceToConnectionId.put(descriptor.deviceId(), descriptor.connectionId());
            }
            if (descriptor.userId() != null && !descriptor.userId().isBlank()) {
                userToConnectionIds.computeIfAbsent(descriptor.userId(), ignored -> ConcurrentHashMap.newKeySet())
                        .add(descriptor.connectionId());
            }
        });
    }

    @Override
    public Mono<Void> unregister(String connectionId) {
        return Mono.fromRunnable(() -> {
            ConnectionRegistration removed = byConnectionId.remove(connectionId);
            if (removed == null) {
                return;
            }
            ConnectionDescriptor descriptor = removed.descriptor();
            if (descriptor.deviceId() != null) {
                deviceToConnectionId.remove(descriptor.deviceId(), connectionId);
            }
            if (descriptor.userId() != null) {
                Set<String> ids = userToConnectionIds.get(descriptor.userId());
                if (ids != null) {
                    ids.remove(connectionId);
                    if (ids.isEmpty()) {
                        userToConnectionIds.remove(descriptor.userId());
                    }
                }
            }
        });
    }

    @Override
    public Optional<WebSocketSession> findByConnectionId(String connectionId) {
        return Optional.ofNullable(byConnectionId.get(connectionId)).map(ConnectionRegistration::session);
    }

    @Override
    public Optional<WebSocketSession> findDeviceSession(String deviceId) {
        return Optional.ofNullable(deviceToConnectionId.get(deviceId)).flatMap(this::findByConnectionId);
    }

    @Override
    public Set<WebSocketSession> findUserSessions(String userId) {
        return userToConnectionIds.getOrDefault(userId, Set.of()).stream()
                .map(this::findByConnectionId)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

}
