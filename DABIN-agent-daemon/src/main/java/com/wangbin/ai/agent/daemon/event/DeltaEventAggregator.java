package com.wangbin.ai.agent.daemon.event;

import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeltaEventAggregator {

    private final Duration window;
    private final int maxChars;
    private final Map<String, Buffer> buffers = new HashMap<>();

    public DeltaEventAggregator(AgentDaemonProperties properties) {
        this.window = properties.getEventAggregationWindow();
        this.maxChars = properties.getEventAggregationMaxChars();
    }

    public synchronized List<AgentEvent> accept(AgentEvent event) {
        if (event.type() == AgentEventType.AGENT_MESSAGE_DELTA
                && event.payload() instanceof AgentMessagePayload payload) {
            String key = event.sessionId() + ":" + payload.messageId();
            Buffer buffer = buffers.computeIfAbsent(key, ignored -> new Buffer(event, Instant.now()));
            buffer.pendingDelta.append(payload.content());
            buffer.fullMessage.append(payload.content());
            if (buffer.pendingDelta.length() >= maxChars || Duration.between(buffer.lastFlush, Instant.now()).compareTo(window) >= 0) {
                return List.of(flushDelta(buffer));
            }
            return List.of();
        }
        if (event.type() == AgentEventType.SESSION_IDLE
                || event.type() == AgentEventType.SESSION_COMPLETED
                || event.type() == AgentEventType.ERROR) {
            List<AgentEvent> flushed = flushSession(event.sessionId());
            flushed.add(event);
            return flushed;
        }
        return List.of(event);
    }

    public synchronized List<AgentEvent> flushSession(String sessionId) {
        List<AgentEvent> result = new ArrayList<>();
        List<String> keys = buffers.keySet().stream()
                .filter(key -> key.startsWith(sessionId + ":"))
                .toList();
        for (String key : keys) {
            Buffer buffer = buffers.remove(key);
            if (buffer != null && !buffer.fullMessage.isEmpty()) {
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
        return event;
    }

    private AgentEvent toDeltaEvent(Buffer buffer, String content) {
        AgentMessagePayload original = (AgentMessagePayload) buffer.seed.payload();
        return new AgentEvent(null, buffer.seed.traceId(), buffer.seed.tenantId(), buffer.seed.userId(),
                buffer.seed.deviceId(), buffer.seed.projectId(), buffer.seed.sessionId(), buffer.seed.seq(),
                buffer.seed.agentType(), AgentEventType.AGENT_MESSAGE_DELTA, null, null,
                new AgentMessagePayload(original.messageId(), original.role(), content, true,
                        original.extensions()),
                buffer.seed.extensions());
    }

    private AgentEvent toFinalMessage(Buffer buffer) {
        AgentMessagePayload original = (AgentMessagePayload) buffer.seed.payload();
        return new AgentEvent(null, buffer.seed.traceId(), buffer.seed.tenantId(), buffer.seed.userId(),
                buffer.seed.deviceId(), buffer.seed.projectId(), buffer.seed.sessionId(), buffer.seed.seq(),
                buffer.seed.agentType(), AgentEventType.AGENT_MESSAGE, null, null,
                new AgentMessagePayload(original.messageId(), original.role(), buffer.fullMessage.toString(), false,
                        original.extensions()),
                buffer.seed.extensions());
    }

    private static class Buffer {

        private final AgentEvent seed;
        private final StringBuilder pendingDelta = new StringBuilder();
        private final StringBuilder fullMessage = new StringBuilder();
        private Instant lastFlush;

        private Buffer(AgentEvent seed, Instant lastFlush) {
            this.seed = seed;
            this.lastFlush = lastFlush;
        }
    }

}
