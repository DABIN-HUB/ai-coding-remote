package com.wangbin.ai.agent.contract.runtime;

import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.session.AgentCapabilities;

import java.util.Map;

public record AgentRuntimeDescriptor(
        AgentType agentType,
        String executable,
        String version,
        boolean installed,
        AgentCapabilities capabilities,
        Map<String, Object> metadata
) {

    public AgentRuntimeDescriptor {
        agentType = agentType == null ? AgentType.UNKNOWN : agentType;
        capabilities = capabilities == null ? AgentCapabilities.unknown() : capabilities;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

}
