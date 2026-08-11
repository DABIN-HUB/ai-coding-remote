package com.wangbin.ai.module.agent.service.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.RelayCommandDispatchPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.COMMAND_DISPATCH_FAILED;

@Service
@RequiredArgsConstructor
public class RedisRelayCommandGateway implements RelayCommandGateway {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void dispatch(RelayCommandDispatchPayload payload) {
        try {
            String channel = AgentCoordinationKeys.relayCommandChannel(payload.targetRelayNodeId());
            stringRedisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw exception(COMMAND_DISPATCH_FAILED);
        }
    }
}
