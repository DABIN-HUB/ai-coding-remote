package com.wangbin.ai.agent.contract.runtime;

import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.session.AgentCapabilities;

import java.time.Instant;

public record RuntimeReportPayload(
        String runtimeId,
        String deviceId,
        AgentType agentType,
        String runtimeType,
        String runtimeVersion,
        String executablePath,
        AgentCapabilities capabilities,
        Instant discoveredAt
) {

    public RuntimeReportPayload {
        agentType = agentType == null ? AgentType.UNKNOWN : agentType;
        discoveredAt = discoveredAt == null ? Instant.now() : discoveredAt;
    }
}
