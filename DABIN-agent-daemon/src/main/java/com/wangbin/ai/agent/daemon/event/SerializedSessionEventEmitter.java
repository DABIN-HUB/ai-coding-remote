package com.wangbin.ai.agent.daemon.event;

import com.wangbin.ai.agent.contract.event.AgentEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Serializes final event numbering and sink emission per platform session.
 * Different sessions may emit concurrently, but one session has a single boundary
 * for assigning seq and publishing the event to observers.
 */
@Component
public class SerializedSessionEventEmitter {

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public void emit(AgentEvent event, LongSupplier sequenceSupplier, Consumer<AgentEvent> sink) {
        if (event == null) {
            return;
        }
        Object lock = locks.computeIfAbsent(event.sessionId(), ignored -> new Object());
        synchronized (lock) {
            AgentEvent finalized = withSeq(event, sequenceSupplier.getAsLong());
            sink.accept(finalized);
        }
    }

    public void releaseSession(String sessionId) {
        locks.remove(sessionId);
    }

    private AgentEvent withSeq(AgentEvent event, long seq) {
        return new AgentEvent(event.eventId(), event.traceId(), event.tenantId(), event.userId(),
                event.deviceId(), event.projectId(), event.sessionId(), seq, event.agentType(), event.type(),
                event.priority(), event.timestamp(), event.payload(), event.extensions());
    }
}
