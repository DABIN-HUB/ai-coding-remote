package com.wangbin.ai.agent.relay.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.RelayCommandDispatchPayload;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class RedisCommandDispatchSubscriber implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final AgentRelayProperties properties;
    private final RelayCommandDispatchHandler dispatchHandler;

    public RedisCommandDispatchSubscriber(RedisMessageListenerContainer listenerContainer, ObjectMapper objectMapper,
                                          AgentRelayProperties properties,
                                          RelayCommandDispatchHandler dispatchHandler) {
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.dispatchHandler = dispatchHandler;
    }

    @PostConstruct
    public void subscribe() {
        listenerContainer.addMessageListener(this,
                new ChannelTopic(AgentCoordinationKeys.relayCommandChannel(properties.getNodeId())));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            RelayCommandDispatchPayload payload = objectMapper.readValue(json, RelayCommandDispatchPayload.class);
            if (!properties.getNodeId().equals(payload.targetRelayNodeId())) {
                return;
            }
            dispatchHandler.dispatch(payload).subscribe();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to dispatch relay command", ex);
        }
    }
}
