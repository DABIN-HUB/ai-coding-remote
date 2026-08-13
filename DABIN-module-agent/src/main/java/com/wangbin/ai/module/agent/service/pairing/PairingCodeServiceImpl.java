package com.wangbin.ai.module.agent.service.pairing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.PairingCodePayload;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.PAIRING_CODE_CONSUME_FAILED;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.PAIRING_CODE_CREATE_FAILED;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.PAIRING_CODE_NOT_EXISTS;

@Service
@RequiredArgsConstructor
public class PairingCodeServiceImpl implements PairingCodeService {

    private static final char[] PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int PAIRING_CODE_PART_LENGTH = 4;
    private static final String PAIRING_CODE_SEPARATOR = "-";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentControlPlaneProperties properties;
    private final RedissonClient redissonClient;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public PairingCodePayload createPairingCode(Long tenantId, Long userId) {
        RLock lock = redissonClient.getLock(AgentCoordinationKeys.pairingCreateLock(tenantId, userId));
        boolean locked = false;
        try {
            locked = lock.tryLock(properties.getPairingLockWaitTime().toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                throw exception(PAIRING_CODE_CREATE_FAILED);
            }
            return createPairingCodeUnderLock(tenantId, userId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw exception(PAIRING_CODE_CREATE_FAILED);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private PairingCodePayload createPairingCodeUnderLock(Long tenantId, Long userId) {
        String activeKey = AgentCoordinationKeys.activePairing(tenantId, userId);
        String oldCode = stringRedisTemplate.opsForValue().get(activeKey);
        if (oldCode != null && !oldCode.isBlank()) {
            stringRedisTemplate.delete(AgentCoordinationKeys.pairing(oldCode));
        }
        stringRedisTemplate.delete(activeKey);
        for (int i = 0; i < properties.getPairingCodeCreateMaxRetries(); i++) {
            PairingCodePayload payload = newPairingPayload(tenantId, userId);
            try {
                Boolean stored = stringRedisTemplate.opsForValue().setIfAbsent(
                        AgentCoordinationKeys.pairing(payload.pairingCode()),
                        objectMapper.writeValueAsString(payload), properties.getPairingCodeTtl());
                if (Boolean.TRUE.equals(stored)) {
                    stringRedisTemplate.opsForValue().set(activeKey, payload.pairingCode(),
                            properties.getPairingCodeTtl());
                    return payload;
                }
            } catch (Exception ex) {
                throw exception(PAIRING_CODE_CREATE_FAILED);
            }
        }
        throw exception(PAIRING_CODE_CREATE_FAILED);
    }

    /**
     * Pairing code is one-time. getAndDelete avoids two daemons binding with the same code.
     */
    @Override
    public PairingCodePayload consumePairingCode(String pairingCode) {
        try {
            String value = stringRedisTemplate.opsForValue().get(AgentCoordinationKeys.pairing(pairingCode));
            if (value == null) {
                throw exception(PAIRING_CODE_NOT_EXISTS);
            }
            PairingCodePayload payload = objectMapper.readValue(value, PairingCodePayload.class);
            return consumePairingCodeUnderLock(pairingCode, payload);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw exception(PAIRING_CODE_CONSUME_FAILED);
        }
    }

    private PairingCodePayload consumePairingCodeUnderLock(String pairingCode, PairingCodePayload payload) {
        RLock lock = redissonClient.getLock(AgentCoordinationKeys.pairingCreateLock(payload.tenantId(),
                payload.userId()));
        boolean locked = false;
        try {
            locked = lock.tryLock(properties.getPairingLockWaitTime().toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                throw exception(PAIRING_CODE_CONSUME_FAILED);
            }
            String value = stringRedisTemplate.opsForValue().getAndDelete(AgentCoordinationKeys.pairing(pairingCode));
            if (value == null) {
                throw exception(PAIRING_CODE_NOT_EXISTS);
            }
            String activeKey = AgentCoordinationKeys.activePairing(payload.tenantId(), payload.userId());
            String activeCode = stringRedisTemplate.opsForValue().get(activeKey);
            if (!pairingCode.equals(activeCode)) {
                throw exception(PAIRING_CODE_NOT_EXISTS);
            }
            stringRedisTemplate.delete(activeKey);
            return payload;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw exception(PAIRING_CODE_CONSUME_FAILED);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private PairingCodePayload newPairingPayload(Long tenantId, Long userId) {
        Instant now = Instant.now();
        return new PairingCodePayload(generateCode(), tenantId, userId, now,
                now.plus(properties.getPairingCodeTtl()));
    }

    private String generateCode() {
        return randomPart(PAIRING_CODE_PART_LENGTH) + PAIRING_CODE_SEPARATOR
                + randomPart(PAIRING_CODE_PART_LENGTH);
    }

    private String randomPart(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(PAIRING_ALPHABET[secureRandom.nextInt(PAIRING_ALPHABET.length)]);
        }
        return builder.toString();
    }
}
