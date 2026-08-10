package com.wangbin.ai.agent.relay.connection;

import org.springframework.web.reactive.socket.WebSocketSession;

public record ConnectionRegistration(
        ConnectionDescriptor descriptor,
        WebSocketSession session
) {
}
