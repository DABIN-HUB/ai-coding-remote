package com.wangbin.ai.agent.contract.session;

import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentSession(
        String platformSessionId,
        String nativeSessionId,
        String tenantId,
        String userId,
        String deviceId,
        String projectId,
        AgentType agentType,
        AgentSessionStatus status,
        AgentCapabilities capabilities,
        Instant createdAt,
        Map<String, Object> metadata
) {

    public AgentSession {
        platformSessionId = platformSessionId == null || platformSessionId.isBlank()
                ? UUID.randomUUID().toString() : platformSessionId;
        agentType = agentType == null ? AgentType.UNKNOWN : agentType;
        status = status == null ? AgentSessionStatus.CREATED : status;
        capabilities = capabilities == null ? AgentCapabilities.unknown() : capabilities;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

}
