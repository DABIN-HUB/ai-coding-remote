package com.wangbin.ai.agent.relay.threading;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RelayRedisSchedulerBoundaryTest {

    @Test
    void relayTicketConsumeRunsBlockingRedisOperationBehindSchedulerBoundary() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/wangbin/ai/agent/relay/auth/RelayTicketAuthenticator.java"));

        assertThat(source).contains("opsForValue().getAndDelete");
        assertThat(source).contains("subscribeOn(Schedulers.boundedElastic())");
    }

    @Test
    void presenceRegisterAndUnregisterRunBlockingRedisOperationsBehindSchedulerBoundary() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/wangbin/ai/agent/relay/presence/RelayPresenceRegistry.java"));

        assertThat(source).contains("stringRedisTemplate.opsForValue().set");
        assertThat(source).contains("stringRedisTemplate.execute");
        assertThat(source).contains("subscribeOn(Schedulers.boundedElastic())");
    }
}
