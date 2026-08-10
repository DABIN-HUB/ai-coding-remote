package com.wangbin.ai.agent.relay.routing;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryRouteRegistry implements RouteRegistry {

    private final Map<String, RouteMetadata> routes = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> registerDevice(RouteMetadata metadata) {
        return Mono.fromRunnable(() -> routes.put(metadata.deviceId(), metadata));
    }

    @Override
    public Mono<Void> unregisterDevice(String deviceId) {
        return Mono.fromRunnable(() -> routes.remove(deviceId));
    }

    @Override
    public Optional<RouteMetadata> findDeviceRoute(String deviceId) {
        return Optional.ofNullable(routes.get(deviceId));
    }

}
