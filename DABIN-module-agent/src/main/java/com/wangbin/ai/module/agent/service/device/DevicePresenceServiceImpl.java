package com.wangbin.ai.module.agent.service.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.DevicePresencePayload;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DevicePresenceServiceImpl implements DevicePresenceService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public DevicePresencePayload getPresence(String deviceId) {
        try {
            String value = stringRedisTemplate.opsForValue().get(AgentCoordinationKeys.devicePresence(deviceId));
            return value == null ? null : objectMapper.readValue(value, DevicePresencePayload.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
