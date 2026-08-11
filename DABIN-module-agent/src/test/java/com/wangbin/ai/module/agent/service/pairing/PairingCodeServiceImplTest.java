package com.wangbin.ai.module.agent.service.pairing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.PairingCodePayload;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PairingCodeServiceImplTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final String PAIRING_CODE = "ABCD-EFGH";
    private static final Duration TEST_PAIRING_TTL = Duration.ofMinutes(3);
    private static final long TEST_PAIRING_TTL_SECONDS = 60L;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final AgentControlPlaneProperties properties = new AgentControlPlaneProperties();
    private final PairingCodeServiceImpl service =
            new PairingCodeServiceImpl(redisTemplate, objectMapper, properties);

    @Test
    void createPairingCodeUsesSafeFormatAndConfiguredTtl() {
        properties.setPairingCodeTtl(TEST_PAIRING_TTL);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(String.class), any(String.class), eq(TEST_PAIRING_TTL)))
                .thenReturn(true);

        PairingCodePayload payload = service.createPairingCode(TEST_TENANT_ID, TEST_USER_ID);

        assertThat(payload.pairingCode()).matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
        assertThat(payload.tenantId()).isEqualTo(TEST_TENANT_ID);
        assertThat(payload.userId()).isEqualTo(TEST_USER_ID);
        verify(valueOperations).setIfAbsent(eq(AgentCoordinationKeys.pairing(payload.pairingCode())),
                any(String.class), eq(TEST_PAIRING_TTL));
    }

    @Test
    void createPairingCodeRetriesWhenGeneratedKeyAlreadyExists() {
        properties.setPairingCodeTtl(TEST_PAIRING_TTL);
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
        PairingCodePayload payload = new PairingCodePayload(PAIRING_CODE, TEST_TENANT_ID, TEST_USER_ID,
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(TEST_PAIRING_TTL_SECONDS));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(AgentCoordinationKeys.pairing(payload.pairingCode())))
                .thenReturn(objectMapper.writeValueAsString(payload));

        PairingCodePayload consumed = service.consumePairingCode(payload.pairingCode());

        assertThat(consumed).isEqualTo(payload);
        verify(valueOperations).getAndDelete(AgentCoordinationKeys.pairing(payload.pairingCode()));
    }
}
