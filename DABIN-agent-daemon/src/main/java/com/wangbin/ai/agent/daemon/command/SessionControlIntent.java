package com.wangbin.ai.agent.daemon.command;

import com.wangbin.ai.agent.contract.enums.SessionControlAction;

import java.time.Instant;

public record SessionControlIntent(
        String sessionId,
        String targetCommandId,
        SessionControlAction action,
        String controlCommandId,
        String reason,
        Instant createdAt
) {
}
