package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.session.AgentCapabilities;

public record RuntimeReportRequest(
        String runtimeId,
        AgentType agentType,
        String runtimeType,
        String runtimeVersion,
        String executablePath,
        AgentCapabilities capabilities
) {
}
