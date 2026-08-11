package com.wangbin.ai.agent.relay.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.RelayTicketPayload;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Consumes relay ticket exactly once during WebSocket HELLO.
 */
@Component
public class RelayTicketAuthenticator {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public RelayTicketAuthenticator(RedissonClient redissonClient, ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }

    public Mono<RelayTicketPayload> consume(String ticket) {
        return Mono.fromCallable(() -> {
            RBucket<String> bucket = redissonClient.getBucket(AgentCoordinationKeys.relayTicket(ticket));
            String value = bucket.getAndDelete();
            return value == null ? null : objectMapper.readValue(value, RelayTicketPayload.class);
        });
    }
}
