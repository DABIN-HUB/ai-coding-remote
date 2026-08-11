package com.wangbin.ai.agent.relay.connection;

import com.wangbin.ai.agent.relay.backpressure.BoundedOutboundMessageQueue;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class InMemoryConnectionManager implements ConnectionManager {

    private final AgentRelayProperties properties;
    private final Map<String, ConnectionContext> byConnectionId = new ConcurrentHashMap<>();
    private final Map<String, String> deviceToConnectionId = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> userToConnectionIds = new ConcurrentHashMap<>();

    public InMemoryConnectionManager(AgentRelayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> register(ConnectionRegistration registration) {
        return Mono.fromRunnable(() -> {
            ConnectionDescriptor descriptor = registration.descriptor();
            ConnectionContext context = new ConnectionContext(descriptor, registration.session(),
                    new BoundedOutboundMessageQueue(properties.getOutboundQueueCapacity()));
            byConnectionId.put(descriptor.connectionId(), context);
            if (descriptor.deviceId() != null && !descriptor.deviceId().isBlank()) {
                String oldConnectionId = deviceToConnectionId.put(descriptor.deviceId(), descriptor.connectionId());
                if (oldConnectionId != null && !oldConnectionId.equals(descriptor.connectionId())) {
                    ConnectionContext old = byConnectionId.remove(oldConnectionId);
                    if (old != null) {
                        old.markClosed();
                        old.session().close().subscribe();
                    }
                }
            }
            if (descriptor.userId() != null) {
                userToConnectionIds.computeIfAbsent(descriptor.userId(), ignored -> ConcurrentHashMap.newKeySet())
                        .add(descriptor.connectionId());
            }
        });
    }

    @Override
    public Mono<Void> unregister(String connectionId) {
        return Mono.fromRunnable(() -> {
            ConnectionContext removed = byConnectionId.remove(connectionId);
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
    public Optional<ConnectionContext> findByConnectionId(String connectionId) {
        return Optional.ofNullable(byConnectionId.get(connectionId));
    }

    @Override
    public Optional<ConnectionContext> findDeviceConnection(String deviceId) {
        return Optional.ofNullable(deviceToConnectionId.get(deviceId)).flatMap(this::findByConnectionId);
    }

    @Override
    public Set<ConnectionContext> findUserConnections(Long userId) {
        return userToConnectionIds.getOrDefault(userId, Set.of()).stream()
                .map(this::findByConnectionId)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

}
