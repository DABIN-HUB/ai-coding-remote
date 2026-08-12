package com.wangbin.ai.agent.daemon.command;

import com.wangbin.ai.agent.contract.enums.SessionControlAction;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded daemon-local registry that correlates native interrupted lifecycle with the platform control command.
 * The state is intentionally in-memory because the native turn itself is in-memory and cannot survive daemon crash.
 */
@Component
public class SessionControlIntentRegistry {

    private final int capacity;
    private final Map<Key, SessionControlIntent> intents = new LinkedHashMap<>();

    @Autowired
    public SessionControlIntentRegistry(AgentDaemonProperties properties) {
        this(properties.getSessionControlIntentCapacity());
    }

    SessionControlIntentRegistry(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized SessionControlReserveResult reserve(String sessionId, String targetCommandId,
                                                            SessionControlAction action, String controlCommandId,
                                                            String reason) {
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
        intents.put(key, new SessionControlIntent(sessionId, targetCommandId, action, controlCommandId, reason,
                Instant.now()));
        return SessionControlReserveResult.RESERVED;
    }

    public synchronized Optional<SessionControlIntent> consume(String sessionId, String targetCommandId) {
        return Optional.ofNullable(intents.remove(new Key(sessionId, targetCommandId)));
    }

    public synchronized void clearSession(String sessionId) {
        intents.keySet().removeIf(key -> key.sessionId().equals(sessionId));
    }

    private record Key(String sessionId, String targetCommandId) {
    }
}
