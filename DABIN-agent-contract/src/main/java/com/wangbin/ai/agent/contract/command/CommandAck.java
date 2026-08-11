package com.wangbin.ai.agent.contract.command;

import java.time.Instant;
import java.util.Map;

public record CommandAck(
        String commandId,
        String sessionId,
        String deviceId,
        CommandAckStatus status,
        String code,
        String message,
        Instant timestamp,
        Map<String, Object> extensions
) {

    public CommandAck {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
