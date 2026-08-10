package com.wangbin.ai.agent.relay.backpressure;

import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

@Component
public class BoundedOutboundMessageQueue implements OutboundMessageQueue {

    private final int capacity;
    private final Queue<OutboundMessage> queue;

    public BoundedOutboundMessageQueue(AgentRelayProperties properties) {
        this.capacity = properties.getOutboundQueueCapacity();
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public boolean offer(OutboundMessage message) {
        return queue.offer(message);
    }

    @Override
    public Optional<OutboundMessage> poll() {
        return Optional.ofNullable(queue.poll());
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

}
