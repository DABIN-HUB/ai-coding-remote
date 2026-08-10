package com.wangbin.ai.agent.daemon.adapter.codex.model;

public record CodexRpcProtocolIssue(
        String code,
        String message,
        String rawLine
) {
}
