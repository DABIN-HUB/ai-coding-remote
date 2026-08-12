package com.wangbin.ai.agent.daemon.command;

import com.wangbin.ai.agent.contract.enums.SessionControlAction;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded daemon-local registry that correlates native interrupted lifecycle with the platform control command.
 * The state is intentionally in-memory because the native turn itself is in-memory and cannot survive daemon crash.
 */
@Component
public class SessionControlIntentRegistry {

    private final int capacity;
    private final Duration terminalTimeout;
    private final Duration timedOutCorrelationTtl;
    private final Map<Key, SessionControlIntent> intents = new LinkedHashMap<>();
    private final Map<Key, SessionControlIntent> timedOutIntents = new LinkedHashMap<>();

    @Autowired
    public SessionControlIntentRegistry(AgentDaemonProperties properties) {
        this(properties.getSessionControlIntentCapacity(), properties.getSessionControlTerminalTimeout(),
                properties.getSessionControlTimedOutCorrelationTtl());
    }

    SessionControlIntentRegistry(int capacity) {
        this(capacity, Duration.ofSeconds(30), Duration.ofMinutes(2));
    }

    SessionControlIntentRegistry(int capacity, Duration terminalTimeout, Duration timedOutCorrelationTtl) {
        this.capacity = Math.max(1, capacity);
        this.terminalTimeout = terminalTimeout == null ? Duration.ofSeconds(30) : terminalTimeout;
        this.timedOutCorrelationTtl = timedOutCorrelationTtl == null ? Duration.ofMinutes(2)
                : timedOutCorrelationTtl;
    }

    public synchronized SessionControlReserveResult reserve(String sessionId, String targetCommandId,
                                                            SessionControlAction action, String controlCommandId,
                                                            String reason) {
        purgeExpiredTimedOut(Instant.now());
        Key key = new Key(sessionId, targetCommandId);
        SessionControlIntent existing = intents.get(key);
        if (existing != null) {
            if (existing.action() == action && existing.controlCommandId().equals(controlCommandId)) {
                return SessionControlReserveResult.DUPLICATE;
            }
            return SessionControlReserveResult.CONFLICT;
        }
        if (intents.size() >= capacity) {
            return SessionControlReserveResult.CAPACITY_EXCEEDED;
        }
        Instant now = Instant.now();
        intents.put(key, new SessionControlIntent(sessionId, targetCommandId, action, controlCommandId, reason,
                now, now.plus(terminalTimeout), null));
        return SessionControlReserveResult.RESERVED;
    }

    public synchronized Optional<SessionControlIntent> consume(String sessionId, String targetCommandId) {
        Key key = new Key(sessionId, targetCommandId);
        SessionControlIntent active = intents.remove(key);
        if (active != null) {
            return Optional.of(active);
        }
        return Optional.ofNullable(timedOutIntents.remove(key));
    }

    public synchronized List<SessionControlIntent> timeoutExpired(Instant now) {
        List<SessionControlIntent> expired = new ArrayList<>();
        for (Map.Entry<Key, SessionControlIntent> entry : List.copyOf(intents.entrySet())) {
            SessionControlIntent intent = entry.getValue();
            if (!intent.deadlineAt().isAfter(now)) {
                intents.remove(entry.getKey());
                SessionControlIntent timedOut = intent.markTimedOut(now);
                timedOutIntents.put(entry.getKey(), timedOut);
                expired.add(timedOut);
            }
        }
        purgeExpiredTimedOut(now);
        return expired;
    }

    public synchronized Optional<SessionControlIntent> timeoutIfExpired(String sessionId, String targetCommandId,
                                                                        Instant now) {
        Key key = new Key(sessionId, targetCommandId);
        SessionControlIntent intent = intents.get(key);
        if (intent == null || intent.deadlineAt().isAfter(now)) {
            purgeExpiredTimedOut(now);
            return Optional.empty();
        }
        intents.remove(key);
        SessionControlIntent timedOut = intent.markTimedOut(now);
        timedOutIntents.put(key, timedOut);
        purgeExpiredTimedOut(now);
        return Optional.of(timedOut);
    }

    public long terminalTimeoutMillis() {
        return Math.max(1L, terminalTimeout.toMillis());
    }

    public synchronized void clearSession(String sessionId) {
        intents.keySet().removeIf(key -> key.sessionId().equals(sessionId));
        timedOutIntents.keySet().removeIf(key -> key.sessionId().equals(sessionId));
    }

    private void purgeExpiredTimedOut(Instant now) {
        timedOutIntents.entrySet().removeIf(entry -> {
            Instant timedOutAt = entry.getValue().timedOutAt();
            return timedOutAt != null && timedOutAt.plus(timedOutCorrelationTtl).isBefore(now);
        });
    }

    private record Key(String sessionId, String targetCommandId) {
    }
}
