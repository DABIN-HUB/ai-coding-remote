package com.wangbin.ai.agent.relay.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.RelayTicketPayload;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Consumes relay ticket exactly once during WebSocket HELLO.
 */
@Component
public class RelayTicketAuthenticator {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RelayTicketAuthenticator(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public Mono<RelayTicketPayload> consume(String ticket) {
        return Mono.fromCallable(() -> {
            String value = stringRedisTemplate.opsForValue().getAndDelete(AgentCoordinationKeys.relayTicket(ticket));
            return value == null ? null : objectMapper.readValue(value, RelayTicketPayload.class);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
