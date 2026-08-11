package com.wangbin.ai.agent.relay.connection;

import com.wangbin.ai.agent.relay.backpressure.BoundedOutboundMessageQueue;
import com.wangbin.ai.agent.relay.backpressure.OutboundMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;

public class ConnectionContext {

    private final ConnectionDescriptor descriptor;
    private final WebSocketSession session;
    private final BoundedOutboundMessageQueue outboundQueue;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicReference<ConnectionAuthState> authState =
            new AtomicReference<>(ConnectionAuthState.CONNECTED_UNAUTHENTICATED);
    private volatile Instant lastPongAt = Instant.now();

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

    public ConnectionAuthState authState() {
        return authState.get();
    }

    public void markAuthenticated() {
        authState.set(ConnectionAuthState.AUTHENTICATED);
    }

    public void markClosed() {
        authState.set(ConnectionAuthState.CLOSED);
    }

    public Instant lastPongAt() {
        return lastPongAt;
    }

    public void markPong() {
        lastPongAt = Instant.now();
    }

}
