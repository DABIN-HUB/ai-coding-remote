package com.wangbin.ai.module.agent.service.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.RelaySubjectType;
import com.wangbin.ai.agent.contract.coordination.RelayTicketPayload;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.RELAY_TICKET_CREATE_FAILED;

@Service
@RequiredArgsConstructor
public class RelayTicketServiceImpl implements RelayTicketService {

    private static final int SECRET_BYTE_LENGTH = 32;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentControlPlaneProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public RelayTicketPayload createUserTicket(Long tenantId, Long userId) {
        return createTicket(RelaySubjectType.USER, tenantId, userId, null);
    }

    @Override
    public RelayTicketPayload createDeviceTicket(Long tenantId, Long userId, String deviceId) {
        return createTicket(RelaySubjectType.DEVICE, tenantId, userId, deviceId);
    }

    private RelayTicketPayload createTicket(RelaySubjectType subjectType, Long tenantId, Long userId, String deviceId) {
        Instant now = Instant.now();
        String ticket = randomSecret();
        RelayTicketPayload payload = new RelayTicketPayload(ticket, subjectType, tenantId, userId, deviceId,
                now, now.plus(properties.getRelayTicketTtl()));
        try {
            stringRedisTemplate.opsForValue().set(AgentCoordinationKeys.relayTicket(ticket),
                    objectMapper.writeValueAsString(payload), properties.getRelayTicketTtl().toMillis(),
                    TimeUnit.MILLISECONDS);
            return payload;
        } catch (Exception ex) {
            throw exception(RELAY_TICKET_CREATE_FAILED);
        }
    }

    private String randomSecret() {
        byte[] bytes = new byte[SECRET_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
