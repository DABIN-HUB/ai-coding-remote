package com.wangbin.ai.agent.relay.websocket;

import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class RelayWebSocketConfiguration {

    private final AgentRelayProperties properties;
    private final RelayWebSocketHandler relayWebSocketHandler;

    public RelayWebSocketConfiguration(AgentRelayProperties properties, RelayWebSocketHandler relayWebSocketHandler) {
        this.properties = properties;
        this.relayWebSocketHandler = relayWebSocketHandler;
    }

    @Bean
    public HandlerMapping relayWebSocketMapping() {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of(properties.getWebsocketPath(), relayWebSocketHandler));
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter relayWebSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
