package com.wangbin.ai.agent.relay.connection;

import com.wangbin.ai.agent.relay.backpressure.BoundedOutboundMessageQueue;
import com.wangbin.ai.agent.relay.backpressure.OutboundMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionContext {

    private final ConnectionDescriptor descriptor;
    private final WebSocketSession session;
    private final BoundedOutboundMessageQueue outboundQueue;
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public ConnectionContext(ConnectionDescriptor descriptor, WebSocketSession session,
                             BoundedOutboundMessageQueue outboundQueue) {
        this.descriptor = descriptor;
        this.session = session;
        this.outboundQueue = outboundQueue;
    }

    public ConnectionDescriptor descriptor() {
        return descriptor;
    }

    public WebSocketSession session() {
        return session;
    }

    public BoundedOutboundMessageQueue outboundQueue() {
        return outboundQueue;
    }

    public boolean tryStartDraining() {
        return draining.compareAndSet(false, true);
    }

    public void finishDraining() {
        draining.set(false);
    }

    public List<OutboundMessage> drainAll() {
        return outboundQueue.drainAll();
    }

}
