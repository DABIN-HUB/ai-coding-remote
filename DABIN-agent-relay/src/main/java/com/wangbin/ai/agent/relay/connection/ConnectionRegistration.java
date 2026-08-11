package com.wangbin.ai.agent.relay.connection;

import com.wangbin.ai.agent.relay.backpressure.ConnectionOutboundChannel;
import org.springframework.web.reactive.socket.WebSocketSession;

public record ConnectionRegistration(
        ConnectionDescriptor descriptor,
        WebSocketSession session,
        ConnectionOutboundChannel outboundChannel
) {

    public ConnectionRegistration(ConnectionDescriptor descriptor, WebSocketSession session) {
        this(descriptor, session, null);
    }
}
