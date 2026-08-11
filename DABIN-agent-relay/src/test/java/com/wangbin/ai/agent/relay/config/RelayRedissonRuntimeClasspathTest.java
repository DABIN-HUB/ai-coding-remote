package com.wangbin.ai.agent.relay.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class RelayRedissonRuntimeClasspathTest {

    @Test
    void redissonSpringDataAdapterIsAvailableAtRuntime() {
        assertThatCode(() -> Class.forName("org.redisson.spring.data.connection.RedissonConnectionFactory"))
                .doesNotThrowAnyException();
    }

}
