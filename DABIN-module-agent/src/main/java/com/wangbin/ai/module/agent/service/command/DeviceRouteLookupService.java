package com.wangbin.ai.module.agent.service.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.DeviceRoutePayload;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeviceRouteLookupService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public DeviceRoutePayload getRoute(String deviceId) {
        try {
            String value = stringRedisTemplate.opsForValue().get(AgentCoordinationKeys.deviceRoute(deviceId));
            return value == null ? null : objectMapper.readValue(value, DeviceRoutePayload.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
