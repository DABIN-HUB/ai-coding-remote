package com.wangbin.ai.module.agent.service.session;

import com.wangbin.ai.module.agent.dal.dataobject.command.AgentCommandDO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.dataobject.project.AgentProjectDO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;

/**
 * Holds the committed platform objects required to route one remote command.
 */
record AgentCommandDispatchContext(
        AgentSessionDO session,
        AgentProjectDO project,
        AgentDeviceDO device,
        AgentCommandDO command,
        String prompt
) {
}
