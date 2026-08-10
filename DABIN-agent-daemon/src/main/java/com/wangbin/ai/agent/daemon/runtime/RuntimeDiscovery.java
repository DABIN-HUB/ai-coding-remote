package com.wangbin.ai.agent.daemon.runtime;

import com.wangbin.ai.agent.contract.enums.AgentType;

public interface RuntimeDiscovery {

    RuntimeDiscoveryResult discover(AgentType agentType);

}
