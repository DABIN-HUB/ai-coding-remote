package com.wangbin.ai.agent.relay.presence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import com.wangbin.ai.agent.relay.connection.ConnectionDescriptor;
import com.wangbin.ai.agent.relay.connection.ConnectionRole;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RelayPresenceRegistryTest {

    private static final String RELAY_NODE_ID = "relay-1";
    private static final String CONNECTION_ID = "conn-1";
    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String DEVICE_ID = "dev-1";
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(90);

    @Test
    void compareDeleteScriptMatchesConnectionIdFieldExactly() throws Exception {
        Field field = RelayPresenceRegistry.class.getDeclaredField("COMPARE_DELETE_SCRIPT");
        field.setAccessible(true);

        String script = (String) field.get(null);

        assertThat(script).contains("cjson.decode");
        assertThat(script).contains("decoded.connectionId == ARGV[1]");
        assertThat(script).doesNotContain("string.find");
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerWritesDevicePresenceAndRouteThroughSpringStringRedisTemplate() {
        CapturingStringRedisTemplate redisTemplate = new CapturingStringRedisTemplate();
        AgentRelayProperties properties = new AgentRelayProperties();
        properties.setPresenceTtl(PRESENCE_TTL);
        ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        RelayPresenceRegistry registry = new RelayPresenceRegistry(redisTemplate, objectMapper, properties);
        ConnectionDescriptor descriptor = new ConnectionDescriptor(CONNECTION_ID, ConnectionRole.DEVICE,
                TENANT_ID, USER_ID, DEVICE_ID, Instant.now());

        registry.register(descriptor, RELAY_NODE_ID).block();

        assertThat(redisTemplate.sets).anySatisfy(set -> {
            assertThat(set.key()).isEqualTo(AgentCoordinationKeys.devicePresence(DEVICE_ID));
            assertThat(set.value()).contains("\"connectionId\":\"" + CONNECTION_ID + "\"");
            assertThat(set.ttl()).isEqualTo(PRESENCE_TTL);
        });
        assertThat(redisTemplate.sets).anySatisfy(set -> {
            assertThat(set.key()).isEqualTo(AgentCoordinationKeys.deviceRoute(DEVICE_ID));
            assertThat(set.value()).contains("\"connectionId\":\"" + CONNECTION_ID + "\"");
            assertThat(set.ttl()).isEqualTo(PRESENCE_TTL);
        });
    }

    private record RedisSet(String key, String value, Duration ttl) {
    }

    private static final class CapturingStringRedisTemplate extends StringRedisTemplate {

        private final List<RedisSet> sets = new CopyOnWriteArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, String> opsForValue() {
            return (ValueOperations<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{ValueOperations.class}, (proxy, method, args) -> {
                        if ("set".equals(method.getName()) && args != null && args.length == 3
                                && args[2] instanceof Duration duration) {
                            sets.add(new RedisSet((String) args[0], (String) args[1], duration));
                            return null;
                        }
                        if ("getOperations".equals(method.getName())) {
                            return (RedisOperations<String, String>) null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private Object defaultValue(Class<?> returnType) {
            if (Boolean.TYPE.equals(returnType)) {
                return false;
            }
            if (Integer.TYPE.equals(returnType)) {
                return 0;
            }
            if (Long.TYPE.equals(returnType)) {
                return 0L;
            }
            return null;
        }
    }
}
