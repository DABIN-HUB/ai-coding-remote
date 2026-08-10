package com.wangbin.ai.agent.relay.backpressure;

import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class BoundedOutboundMessageQueue implements OutboundMessageQueue {

    private final int capacity;
    private final ArrayDeque<OutboundMessage> queue;

    public BoundedOutboundMessageQueue(AgentRelayProperties properties) {
        this(properties.getOutboundQueueCapacity());
    }

    public BoundedOutboundMessageQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("outbound queue capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(capacity);
    }

    @Override
    public synchronized boolean offer(OutboundMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        if (queue.size() < capacity) {
            queue.addLast(message);
            return true;
        }
        boolean madeRoom = switch (message.priority()) {
            case TRANSIENT -> false;
            case NORMAL -> removeFirst(EventPriority.TRANSIENT);
            case IMPORTANT -> removeFirst(EventPriority.TRANSIENT, EventPriority.NORMAL);
            case CRITICAL -> removeFirst(EventPriority.TRANSIENT, EventPriority.NORMAL, EventPriority.IMPORTANT);
        };
        if (!madeRoom) {
            return false;
        }
        queue.addLast(message);
        return true;
    }

    @Override
    public synchronized Optional<OutboundMessage> poll() {
        return Optional.ofNullable(queue.poll());
    }

    @Override
    public synchronized int size() {
        return queue.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    public synchronized List<OutboundMessage> drainAll() {
        List<OutboundMessage> messages = new ArrayList<>(queue.size());
        OutboundMessage message;
        while ((message = queue.poll()) != null) {
            messages.add(message);
        }
        return messages;
    }

    private boolean removeFirst(EventPriority... removablePriorities) {
        for (OutboundMessage message : queue) {
            if (contains(removablePriorities, message.priority())) {
                queue.remove(message);
                return true;
            }
        }
        return false;
    }

    private boolean contains(EventPriority[] priorities, EventPriority priority) {
        for (EventPriority candidate : priorities) {
            if (candidate == priority) {
                return true;
            }
        }
        return false;
    }

}
