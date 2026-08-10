package com.wangbin.ai.agent.contract.command;

import com.wangbin.ai.agent.contract.enums.CommandType;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AgentCommand(
        String commandId,
        String traceId,
        Long tenantId,
        Long userId,
        String deviceId,
        String sessionId,
        CommandType commandType,
        AgentCommandPayload payload,
        Instant createdAt,
        Instant expireAt,
        Map<String, Object> extensions
) {

    public AgentCommand {
        commandId = commandId == null || commandId.isBlank() ? UUID.randomUUID().toString() : commandId;
        commandType = Objects.requireNonNull(commandType, "commandType must not be null");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
