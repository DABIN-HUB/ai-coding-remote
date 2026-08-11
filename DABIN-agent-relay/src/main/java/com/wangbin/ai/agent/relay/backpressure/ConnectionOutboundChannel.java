package com.wangbin.ai.agent.relay.backpressure;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-connection outbound channel. It provides one bounded, serialized stream
 * that is consumed by exactly one WebSocketSession.send publisher.
 */
public class ConnectionOutboundChannel {

    private final int capacity;
    private final Sinks.Many<OutboundMessage> sink;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ConnectionOutboundChannel(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("outbound channel capacity must be positive");
        }
        this.capacity = capacity;
        this.sink = Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(capacity));
    }

    public synchronized boolean enqueue(OutboundMessage message) {
        if (closed.get()) {
            return false;
        }
        Sinks.EmitResult result = sink.tryEmitNext(message);
        return result.isSuccess();
    }

    public Flux<OutboundMessage> messages() {
        return sink.asFlux();
    }

    public void complete() {
        if (closed.compareAndSet(false, true)) {
            sink.tryEmitComplete();
        }
    }

    public int capacity() {
        return capacity;
    }

}
