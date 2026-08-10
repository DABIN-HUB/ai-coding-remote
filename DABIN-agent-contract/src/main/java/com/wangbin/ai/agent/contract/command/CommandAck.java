package com.wangbin.ai.agent.contract.command;

import com.wangbin.ai.agent.contract.enums.CommandStatus;

import java.time.Instant;
import java.util.Map;

public record CommandAck(
        String commandId,
        CommandStatus status,
        String daemonId,
        String message,
        Instant ackAt,
        Map<String, Object> extensions
) {

    public CommandAck {
        ackAt = ackAt == null ? Instant.now() : ackAt;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
