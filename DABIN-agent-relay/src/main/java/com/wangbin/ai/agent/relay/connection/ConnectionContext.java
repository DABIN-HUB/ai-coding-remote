package com.wangbin.ai.agent.relay.connection;

import com.wangbin.ai.agent.relay.backpressure.ConnectionOutboundChannel;
import com.wangbin.ai.agent.relay.backpressure.OutboundMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;

public class ConnectionContext {

    private final ConnectionDescriptor descriptor;
    private final WebSocketSession session;
    private final ConnectionOutboundChannel outboundChannel;
    private final AtomicBoolean outboundClosed = new AtomicBoolean(false);
    private final AtomicReference<ConnectionAuthState> authState =
            new AtomicReference<>(ConnectionAuthState.CONNECTED_UNAUTHENTICATED);
    private volatile Instant lastPongAt = Instant.now();

    public ConnectionContext(ConnectionDescriptor descriptor, WebSocketSession session,
                             ConnectionOutboundChannel outboundChannel) {
        this.descriptor = descriptor;
        this.session = session;
        this.outboundChannel = outboundChannel;
    }

    public ConnectionDescriptor descriptor() {
        return descriptor;
    }

    public WebSocketSession session() {
        return session;
    }

    public ConnectionOutboundChannel outboundChannel() {
        return outboundChannel;
    }

    public boolean enqueue(OutboundMessage message) {
        return outboundChannel.enqueue(message);
    }

    public Flux<OutboundMessage> outboundMessages() {
        return outboundChannel.messages();
    }

    public ConnectionAuthState authState() {
        return authState.get();
    }

    public void markAuthenticated() {
        authState.set(ConnectionAuthState.AUTHENTICATED);
    }

    public void markClosed() {
        authState.set(ConnectionAuthState.CLOSED);
        if (outboundClosed.compareAndSet(false, true)) {
            outboundChannel.complete();
        }
    }

    public Instant lastPongAt() {
        return lastPongAt;
    }

    public void markPong() {
        lastPongAt = Instant.now();
    }

}
