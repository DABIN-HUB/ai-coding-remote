package com.wangbin.ai.module.agent.service.pairing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.PairingCodePayload;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.PAIRING_CODE_NOT_EXISTS;

@Service
@RequiredArgsConstructor
public class PairingCodeServiceImpl implements PairingCodeService {

    private static final char[] PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentControlPlaneProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public PairingCodePayload createPairingCode(Long tenantId, Long userId) {
        Instant now = Instant.now();
        String code = generateCode();
        PairingCodePayload payload = new PairingCodePayload(code, tenantId, userId, now,
                now.plus(properties.getPairingCodeTtl()));
        try {
            stringRedisTemplate.opsForValue().set(AgentCoordinationKeys.pairing(code),
                    objectMapper.writeValueAsString(payload), properties.getPairingCodeTtl().toMillis(),
                    TimeUnit.MILLISECONDS);
            return payload;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to store pairing code", ex);
        }
    }

    /**
     * Pairing code is one-time. getAndDelete avoids two daemons binding with the same code.
     */
    @Override
    public PairingCodePayload consumePairingCode(String pairingCode) {
        try {
            String value = stringRedisTemplate.opsForValue().getAndDelete(AgentCoordinationKeys.pairing(pairingCode));
            if (value == null) {
                throw exception(PAIRING_CODE_NOT_EXISTS);
            }
            return objectMapper.readValue(value, PairingCodePayload.class);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to consume pairing code", ex);
        }
    }

    private String generateCode() {
        return randomPart(4) + "-" + randomPart(4);
    }

    private String randomPart(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(PAIRING_ALPHABET[secureRandom.nextInt(PAIRING_ALPHABET.length)]);
        }
        return builder.toString();
    }
}
