package com.wangbin.ai.agent.contract.permission;

public record FileChangeSummary(
        String path,
        String changeType,
        boolean truncated
) {
}
