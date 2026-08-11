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

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final AgentControlPlaneProperties properties = new AgentControlPlaneProperties();
    private final PairingCodeServiceImpl service =
            new PairingCodeServiceImpl(redisTemplate, objectMapper, properties);

    @Test
    void createPairingCodeUsesSafeFormatAndConfiguredTtl() {
        properties.setPairingCodeTtl(Duration.ofMinutes(3));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(String.class), any(String.class), eq(Duration.ofMinutes(3))))
                .thenReturn(true);

        PairingCodePayload payload = service.createPairingCode(1L, 11L);

        assertThat(payload.pairingCode()).matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
        assertThat(payload.tenantId()).isEqualTo(1L);
        assertThat(payload.userId()).isEqualTo(11L);
        verify(valueOperations).setIfAbsent(eq(AgentCoordinationKeys.pairing(payload.pairingCode())),
                any(String.class), eq(Duration.ofMinutes(3)));
    }

    @Test
    void createPairingCodeRetriesWhenGeneratedKeyAlreadyExists() {
        properties.setPairingCodeTtl(Duration.ofMinutes(3));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(String.class), any(String.class), eq(Duration.ofMinutes(3))))
                .thenReturn(false)
                .thenReturn(true);

        PairingCodePayload payload = service.createPairingCode(1L, 11L);

        assertThat(payload.pairingCode()).isNotBlank();
        verify(valueOperations, times(2)).setIfAbsent(any(String.class), any(String.class),
                eq(Duration.ofMinutes(3)));
    }

    @Test
    void consumePairingCodeUsesAtomicGetAndDelete() throws Exception {
        PairingCodePayload payload = new PairingCodePayload("ABCD-EFGH", 1L, 11L,
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(60));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(AgentCoordinationKeys.pairing(payload.pairingCode())))
                .thenReturn(objectMapper.writeValueAsString(payload));

        PairingCodePayload consumed = service.consumePairingCode(payload.pairingCode());

        assertThat(consumed).isEqualTo(payload);
        verify(valueOperations).getAndDelete(AgentCoordinationKeys.pairing(payload.pairingCode()));
    }
}
