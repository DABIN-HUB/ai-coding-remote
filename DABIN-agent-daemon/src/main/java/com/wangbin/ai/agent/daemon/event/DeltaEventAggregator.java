package com.wangbin.ai.agent.daemon.event;

import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

@Component
public class DeltaEventAggregator {

    private final Duration window;
    private final int maxChars;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Buffer> buffers = new HashMap<>();

    public DeltaEventAggregator(AgentDaemonProperties properties, ScheduledExecutorService agentEventScheduler) {
        this.window = properties.getEventAggregationWindow();
        this.maxChars = properties.getEventAggregationMaxChars();
        this.scheduler = agentEventScheduler;
    }

    public synchronized List<AgentEvent> accept(AgentEvent event, LongSupplier sequenceSupplier,
                                                Consumer<AgentEvent> timedFlushConsumer) {
        if (event.type() == AgentEventType.AGENT_MESSAGE_DELTA
                && event.payload() instanceof AgentMessagePayload payload) {
            String key = event.sessionId() + ":" + payload.messageId();
            Buffer buffer = buffers.computeIfAbsent(key, ignored -> new Buffer(event, sequenceSupplier,
                    timedFlushConsumer));
            String content = payload.content() == null ? "" : payload.content();
            buffer.pendingDelta.append(content);
            buffer.fullMessage.append(content);
            scheduleFlush(key, buffer);
            if (buffer.pendingDelta.length() >= maxChars || Duration.between(buffer.lastFlush, Instant.now()).compareTo(window) >= 0) {
                return List.of(flushDelta(buffer));
            }
            return List.of();
        }
        if (event.type() == AgentEventType.SESSION_IDLE
                || event.type() == AgentEventType.SESSION_COMPLETED
                || event.type() == AgentEventType.ERROR) {
            List<AgentEvent> flushed = flushSession(event.sessionId(), sequenceSupplier, timedFlushConsumer);
            flushed.add(copyWithSeq(event, sequenceSupplier.getAsLong()));
            return flushed;
        }
        return List.of(event);
    }

    public synchronized List<AgentEvent> flushSession(String sessionId, LongSupplier sequenceSupplier,
                                                      Consumer<AgentEvent> timedFlushConsumer) {
        List<AgentEvent> result = new ArrayList<>();
        List<String> keys = buffers.keySet().stream()
                .filter(key -> key.startsWith(sessionId + ":"))
                .toList();
        for (String key : keys) {
            Buffer buffer = buffers.remove(key);
            if (buffer != null && !buffer.fullMessage.isEmpty()) {
                buffer.cancelTimer();
                if (!buffer.pendingDelta.isEmpty()) {
                    result.add(toDeltaEvent(buffer, buffer.pendingDelta.toString()));
                }
                result.add(toFinalMessage(buffer));
            }
        }
        return result;
    }

    private AgentEvent flushDelta(Buffer buffer) {
        AgentEvent event = toDeltaEvent(buffer, buffer.pendingDelta.toString());
        buffer.lastFlush = Instant.now();
        buffer.pendingDelta.setLength(0);
        buffer.cancelTimer();
        return event;
    }

    private AgentEvent toDeltaEvent(Buffer buffer, String content) {
        AgentMessagePayload original = (AgentMessagePayload) buffer.seed.payload();
        return new AgentEvent(null, buffer.seed.traceId(), buffer.seed.tenantId(), buffer.seed.userId(),
                buffer.seed.deviceId(), buffer.seed.projectId(), buffer.seed.sessionId(), buffer.sequenceSupplier.getAsLong(),
                buffer.seed.agentType(), AgentEventType.AGENT_MESSAGE_DELTA, null, null,
                new AgentMessagePayload(original.messageId(), original.role(), content, true,
                        original.extensions()),
                buffer.seed.extensions());
    }

    private AgentEvent toFinalMessage(Buffer buffer) {
        AgentMessagePayload original = (AgentMessagePayload) buffer.seed.payload();
        return new AgentEvent(null, buffer.seed.traceId(), buffer.seed.tenantId(), buffer.seed.userId(),
                buffer.seed.deviceId(), buffer.seed.projectId(), buffer.seed.sessionId(), buffer.sequenceSupplier.getAsLong(),
                buffer.seed.agentType(), AgentEventType.AGENT_MESSAGE, null, null,
                new AgentMessagePayload(original.messageId(), original.role(), buffer.fullMessage.toString(), false,
                        original.extensions()),
                buffer.seed.extensions());
    }

    private AgentEvent copyWithSeq(AgentEvent event, long seq) {
        return new AgentEvent(event.eventId(), event.traceId(), event.tenantId(), event.userId(),
                event.deviceId(), event.projectId(), event.sessionId(), seq, event.agentType(), event.type(),
                event.priority(), event.timestamp(), event.payload(), event.extensions());
    }

    private void scheduleFlush(String key, Buffer buffer) {
        if (buffer.scheduledFuture != null && !buffer.scheduledFuture.isDone()) {
            return;
        }
        buffer.scheduledFuture = scheduler.schedule(() -> flushTimed(key), window.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void flushTimed(String key) {
        AgentEvent event;
        Consumer<AgentEvent> consumer;
        synchronized (this) {
            Buffer buffer = buffers.get(key);
            if (buffer == null || buffer.pendingDelta.isEmpty()) {
                return;
            }
            event = flushDelta(buffer);
            consumer = buffer.timedFlushConsumer;
        }
        consumer.accept(event);
    }

    @PreDestroy
    public synchronized void shutdown() {
        buffers.values().forEach(Buffer::cancelTimer);
        buffers.clear();
    }

    private static class Buffer {

        private final AgentEvent seed;
        private final LongSupplier sequenceSupplier;
        private final Consumer<AgentEvent> timedFlushConsumer;
        private final StringBuilder pendingDelta = new StringBuilder();
        private final StringBuilder fullMessage = new StringBuilder();
        private Instant lastFlush = Instant.now();
        private ScheduledFuture<?> scheduledFuture;

        private Buffer(AgentEvent seed, LongSupplier sequenceSupplier, Consumer<AgentEvent> timedFlushConsumer) {
            this.seed = seed;
            this.sequenceSupplier = sequenceSupplier;
            this.timedFlushConsumer = timedFlushConsumer;
        }

        private void cancelTimer() {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                scheduledFuture = null;
            }
        }
    }

}
