package com.wangbin.ai.agent.daemon.adapter.codex;

import com.wangbin.ai.agent.contract.enums.AgentType;

public record CodexSessionContext(
        String platformSessionId,
        String nativeSessionId,
        String tenantId,
        String userId,
        String deviceId,
        String projectId,
        String workspacePath,
        AgentType agentType
) {
}
