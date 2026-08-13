package com.wangbin.ai.module.agent.service.pairing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.PairingCodePayload;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PairingCodeServiceImplTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final String PAIRING_CODE = "ABCD-EFGH";
    private static final String OLD_PAIRING_CODE = "AAAA-BBBB";
    private static final Duration TEST_PAIRING_TTL = Duration.ofMinutes(3);
    private static final long TEST_PAIRING_TTL_SECONDS = 60L;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final AgentControlPlaneProperties properties = new AgentControlPlaneProperties();
    private final PairingCodeServiceImpl service =
            new PairingCodeServiceImpl(redisTemplate, objectMapper, properties, redissonClient);

    @Test
    void createPairingCodeUsesSafeFormatAndConfiguredTtl() throws Exception {
        properties.setPairingCodeTtl(TEST_PAIRING_TTL);
        mockCreateLock();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(String.class), any(String.class), eq(TEST_PAIRING_TTL)))
                .thenReturn(true);

        PairingCodePayload payload = service.createPairingCode(TEST_TENANT_ID, TEST_USER_ID);

        assertThat(payload.pairingCode()).matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
        assertThat(payload.tenantId()).isEqualTo(TEST_TENANT_ID);
        assertThat(payload.userId()).isEqualTo(TEST_USER_ID);
        verify(valueOperations).setIfAbsent(eq(AgentCoordinationKeys.pairing(payload.pairingCode())),
                any(String.class), eq(TEST_PAIRING_TTL));
        verify(valueOperations).set(AgentCoordinationKeys.activePairing(TEST_TENANT_ID, TEST_USER_ID),
                payload.pairingCode(), TEST_PAIRING_TTL);
    }

    @Test
    void createPairingCodeRetriesWhenGeneratedKeyAlreadyExists() throws Exception {
        properties.setPairingCodeTtl(TEST_PAIRING_TTL);
        mockCreateLock();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(String.class), any(String.class), eq(TEST_PAIRING_TTL)))
                .thenReturn(false)
                .thenReturn(true);

        PairingCodePayload payload = service.createPairingCode(TEST_TENANT_ID, TEST_USER_ID);

        assertThat(payload.pairingCode()).isNotBlank();
        verify(valueOperations, times(2)).setIfAbsent(any(String.class), any(String.class),
                eq(TEST_PAIRING_TTL));
    }

    @Test
    void consumePairingCodeUsesAtomicGetAndDelete() throws Exception {
        PairingCodePayload payload = payload(PAIRING_CODE);
        String serializedPayload = objectMapper.writeValueAsString(payload);
        mockCreateLock();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(AgentCoordinationKeys.pairing(payload.pairingCode())))
                .thenReturn(serializedPayload);
        when(valueOperations.getAndDelete(AgentCoordinationKeys.pairing(payload.pairingCode())))
                .thenReturn(serializedPayload);
        when(valueOperations.get(AgentCoordinationKeys.activePairing(TEST_TENANT_ID, TEST_USER_ID)))
                .thenReturn(payload.pairingCode());

        PairingCodePayload consumed = service.consumePairingCode(payload.pairingCode());

        assertThat(consumed).isEqualTo(payload);
        verify(valueOperations).getAndDelete(AgentCoordinationKeys.pairing(payload.pairingCode()));
        verify(redisTemplate).delete(AgentCoordinationKeys.activePairing(TEST_TENANT_ID, TEST_USER_ID));
    }

    @Test
    void regenerateDeletesPreviousActiveCode() throws Exception {
        properties.setPairingCodeTtl(TEST_PAIRING_TTL);
        mockCreateLock();
        String activeKey = AgentCoordinationKeys.activePairing(TEST_TENANT_ID, TEST_USER_ID);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(activeKey)).thenReturn(OLD_PAIRING_CODE);
        when(valueOperations.setIfAbsent(any(String.class), any(String.class), eq(TEST_PAIRING_TTL)))
                .thenReturn(true);

        PairingCodePayload payload = service.createPairingCode(TEST_TENANT_ID, TEST_USER_ID);

        verify(redisTemplate).delete(AgentCoordinationKeys.pairing(OLD_PAIRING_CODE));
        verify(redisTemplate).delete(activeKey);
        verify(valueOperations).set(activeKey, payload.pairingCode(), TEST_PAIRING_TTL);
    }

    @Test
    void consumeRejectsRegeneratedOldCodeEvenIfCodeKeyStillExists() throws Exception {
        PairingCodePayload payload = payload(OLD_PAIRING_CODE);
        String serializedPayload = objectMapper.writeValueAsString(payload);
        mockCreateLock();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(AgentCoordinationKeys.pairing(OLD_PAIRING_CODE)))
                .thenReturn(serializedPayload);
        when(valueOperations.getAndDelete(AgentCoordinationKeys.pairing(OLD_PAIRING_CODE)))
                .thenReturn(serializedPayload);
        when(valueOperations.get(AgentCoordinationKeys.activePairing(TEST_TENANT_ID, TEST_USER_ID)))
                .thenReturn(PAIRING_CODE);

        assertThatThrownBy(() -> service.consumePairingCode(OLD_PAIRING_CODE))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void consumeMissingPairingCodeRejectsExpiredOrReplayCode() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(AgentCoordinationKeys.pairing(PAIRING_CODE))).thenReturn(null);

        assertThatThrownBy(() -> service.consumePairingCode(PAIRING_CODE))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void consumedPairingCodeCannotReplay() throws Exception {
        properties.setPairingCodeTtl(TEST_PAIRING_TTL);
        Map<String, String> values = new ConcurrentHashMap<>();
        mockRedisMap(values);
        mockCreateLock();
        PairingCodePayload payload = payload(PAIRING_CODE);
        values.put(AgentCoordinationKeys.pairing(PAIRING_CODE), objectMapper.writeValueAsString(payload));
        values.put(AgentCoordinationKeys.activePairing(TEST_TENANT_ID, TEST_USER_ID), PAIRING_CODE);

        assertThat(service.consumePairingCode(PAIRING_CODE)).isEqualTo(payload);
        assertThatThrownBy(() -> service.consumePairingCode(PAIRING_CODE))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void concurrentCreateKeepsOnlyLatestActivePairingCode() throws Exception {
        properties.setPairingCodeTtl(TEST_PAIRING_TTL);
        Map<String, String> values = new ConcurrentHashMap<>();
        ReentrantLock javaLock = new ReentrantLock();
        mockRedisMap(values);
        when(redissonClient.getLock(AgentCoordinationKeys.pairingCreateLock(TEST_TENANT_ID, TEST_USER_ID)))
                .thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(invocation ->
                javaLock.tryLock((Long) invocation.getArgument(0), invocation.getArgument(1)));
        when(lock.isHeldByCurrentThread()).thenAnswer(invocation -> javaLock.isHeldByCurrentThread());
        doAnswer(invocation -> {
            javaLock.unlock();
            return null;
        }).when(lock).unlock();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<PairingCodePayload> first = executor.submit(() -> service.createPairingCode(TEST_TENANT_ID,
                    TEST_USER_ID));
            Future<PairingCodePayload> second = executor.submit(() -> service.createPairingCode(TEST_TENANT_ID,
                    TEST_USER_ID));

            PairingCodePayload firstPayload = first.get(2, TimeUnit.SECONDS);
            PairingCodePayload secondPayload = second.get(2, TimeUnit.SECONDS);
            String activeCode = values.get(AgentCoordinationKeys.activePairing(TEST_TENANT_ID, TEST_USER_ID));
            PairingCodePayload active = firstPayload.pairingCode().equals(activeCode) ? firstPayload : secondPayload;
            PairingCodePayload stale = firstPayload.pairingCode().equals(activeCode) ? secondPayload : firstPayload;

            assertThat(service.consumePairingCode(active.pairingCode())).isEqualTo(active);
            assertThatThrownBy(() -> service.consumePairingCode(stale.pairingCode()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    private void mockCreateLock() throws Exception {
        when(redissonClient.getLock(AgentCoordinationKeys.pairingCreateLock(TEST_TENANT_ID, TEST_USER_ID)))
                .thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    private void mockRedisMap(Map<String, String> values) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenAnswer(invocation -> {
            Object key = invocation.getArgument(0, Object.class);
            return values.get(String.valueOf(key));
        });
        when(valueOperations.getAndDelete(any(String.class))).thenAnswer(invocation ->
                values.remove(invocation.getArgument(0, String.class)));
        when(valueOperations.setIfAbsent(any(String.class), any(String.class), eq(TEST_PAIRING_TTL)))
                .thenAnswer(invocation -> values.putIfAbsent(invocation.getArgument(0, String.class),
                        invocation.getArgument(1, String.class)) == null);
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0, String.class), invocation.getArgument(1, String.class));
            return null;
        }).when(valueOperations).set(any(String.class), any(String.class), eq(TEST_PAIRING_TTL));
        when(redisTemplate.delete(any(String.class))).thenAnswer(invocation ->
                values.remove(invocation.getArgument(0, String.class)) != null);
    }

    private PairingCodePayload payload(String pairingCode) {
        return new PairingCodePayload(pairingCode, TEST_TENANT_ID, TEST_USER_ID,
                Instant.now(), Instant.now().plusSeconds(TEST_PAIRING_TTL_SECONDS));
    }
}
