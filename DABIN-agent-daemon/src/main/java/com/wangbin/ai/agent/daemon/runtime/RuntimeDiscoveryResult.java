package com.wangbin.ai.agent.daemon.runtime;

import com.wangbin.ai.agent.contract.enums.AgentType;

import java.nio.file.Path;
import java.util.Map;

public record RuntimeDiscoveryResult(
        AgentType agentType,
        RuntimeInstallStatus status,
        String executable,
        String version,
        Path resolvedPath,
        String diagnostic,
        Map<String, Object> metadata
) {

    public RuntimeDiscoveryResult {
        agentType = agentType == null ? AgentType.UNKNOWN : agentType;
        status = status == null ? RuntimeInstallStatus.UNKNOWN : status;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

}
