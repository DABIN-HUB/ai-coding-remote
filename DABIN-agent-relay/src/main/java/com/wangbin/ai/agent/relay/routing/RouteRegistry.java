package com.wangbin.ai.agent.relay.routing;

import reactor.core.publisher.Mono;

import java.util.Optional;

public interface RouteRegistry {

    Mono<Void> registerDevice(RouteMetadata metadata);

    Mono<Void> unregisterDevice(String deviceId);

    Optional<RouteMetadata> findDeviceRoute(String deviceId);

}
