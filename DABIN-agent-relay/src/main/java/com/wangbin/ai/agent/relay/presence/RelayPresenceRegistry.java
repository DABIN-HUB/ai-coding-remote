package com.wangbin.ai.agent.relay.presence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.*;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import com.wangbin.ai.agent.relay.connection.ConnectionDescriptor;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;

/**
 * Stores route metadata in Redis. Cleanup uses compare-and-delete to prevent
 * an old connection finally block from deleting a newer route for the same device.
 */
@Component
public class RelayPresenceRegistry {

    private static final String COMPARE_DELETE_SCRIPT = """
            local current = redis.call('GET', KEYS[1])
            if not current then
              return 0
            end
            local ok, decoded = pcall(cjson.decode, current)
            if ok and decoded and decoded.connectionId == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final AgentRelayProperties properties;

    public RelayPresenceRegistry(RedissonClient redissonClient, ObjectMapper objectMapper,
                                 AgentRelayProperties properties) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Mono<Void> register(ConnectionDescriptor descriptor, String relayNodeId) {
        return Mono.<Void>fromRunnable(() -> {
            Instant now = Instant.now();
            if (descriptor.deviceId() != null) {
                DevicePresencePayload presence = new DevicePresencePayload(relayNodeId, descriptor.connectionId(),
                        descriptor.tenantId(), descriptor.userId(), descriptor.deviceId(), now);
                DeviceRoutePayload route = new DeviceRoutePayload(relayNodeId, descriptor.connectionId(),
                        descriptor.tenantId(), descriptor.userId(), descriptor.deviceId(), now, now);
                setJson(AgentCoordinationKeys.devicePresence(descriptor.deviceId()), presence);
                setJson(AgentCoordinationKeys.deviceRoute(descriptor.deviceId()), route);
            } else {
                UserRoutePayload route = new UserRoutePayload(relayNodeId, descriptor.connectionId(),
                        descriptor.tenantId(), descriptor.userId(), now, now);
                setJson(AgentCoordinationKeys.userRoute(descriptor.userId(), descriptor.connectionId()), route);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> refresh(ConnectionDescriptor descriptor, String relayNodeId) {
        return register(descriptor, relayNodeId);
    }

    public Mono<Void> unregister(ConnectionDescriptor descriptor) {
        return Mono.<Void>fromRunnable(() -> {
            if (descriptor.deviceId() != null) {
                compareDelete(AgentCoordinationKeys.devicePresence(descriptor.deviceId()), descriptor.connectionId());
                compareDelete(AgentCoordinationKeys.deviceRoute(descriptor.deviceId()), descriptor.connectionId());
            } else if (descriptor.userId() != null) {
                redissonClient.getBucket(AgentCoordinationKeys.userRoute(descriptor.userId(),
                        descriptor.connectionId())).delete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void setJson(String key, Object value) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(objectMapper.writeValueAsString(value), properties.getPresenceTtl());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to write relay presence", ex);
        }
    }

    private void compareDelete(String key, String connectionId) {
        redissonClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, COMPARE_DELETE_SCRIPT,
                RScript.ReturnType.LONG, List.of(key), connectionId);
    }
}
