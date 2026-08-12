package com.wangbin.ai.module.agent.service.session;

import com.wangbin.ai.agent.contract.enums.SessionControlAction;
import com.wangbin.ai.module.agent.dal.dataobject.command.AgentCommandDO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.dataobject.project.AgentProjectDO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;

/**
 * Holds the committed platform objects required to route one session control command.
 */
record AgentSessionControlDispatchContext(
        AgentSessionDO session,
        AgentProjectDO project,
        AgentDeviceDO device,
        AgentCommandDO command,
        SessionControlAction action,
        String targetCommandId,
        String reason
) {
}
