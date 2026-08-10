package com.wangbin.ai.agent.contract.event;

import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.EventPriority;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AgentEvent(
        String eventId,
        String traceId,
        Long tenantId,
        Long userId,
        String deviceId,
        String projectId,
        String sessionId,
        long seq,
        AgentType agentType,
        AgentEventType type,
        EventPriority priority,
        Instant timestamp,
        AgentEventPayload payload,
        Map<String, Object> extensions
) {

    public AgentEvent {
        eventId = eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : eventId;
        agentType = agentType == null ? AgentType.UNKNOWN : agentType;
        type = Objects.requireNonNull(type, "type must not be null");
        priority = priority == null ? type.defaultPriority() : priority;
        timestamp = timestamp == null ? Instant.now() : timestamp;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public static AgentEvent of(String traceId, Long tenantId, Long userId, String deviceId, String projectId,
                                String sessionId, long seq, AgentType agentType, AgentEventType type,
                                AgentEventPayload payload) {
        return new AgentEvent(null, traceId, tenantId, userId, deviceId, projectId, sessionId, seq, agentType,
                type, null, null, payload, Map.of());
    }

}
