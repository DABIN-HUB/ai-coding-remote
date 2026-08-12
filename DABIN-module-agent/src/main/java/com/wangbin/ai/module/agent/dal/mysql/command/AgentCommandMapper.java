package com.wangbin.ai.module.agent.dal.mysql.command;

import com.wangbin.ai.framework.mybatis.core.mapper.BaseMapperX;
import com.wangbin.ai.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.module.agent.dal.dataobject.command.AgentCommandDO;
import com.wangbin.ai.module.agent.enums.AgentCommandDbStatus;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentCommandMapper extends BaseMapperX<AgentCommandDO> {

    default AgentCommandDO selectByCommandId(String commandId) {
        return selectOne(AgentCommandDO::getCommandId, commandId);
    }

    default AgentCommandDO selectByClientRequestId(Long sessionId, Long ownerUserId, String requestId) {
        return selectOne(new LambdaQueryWrapperX<AgentCommandDO>()
                .eq(AgentCommandDO::getSessionId, sessionId)
                .eq(AgentCommandDO::getOwnerUserId, ownerUserId)
                .eq(AgentCommandDO::getRequestId, requestId));
    }

    default AgentCommandDO selectByClientRequestId(Long sessionId, Long ownerUserId, String commandType,
                                                   String requestId) {
        return selectOne(new LambdaQueryWrapperX<AgentCommandDO>()
                .eq(AgentCommandDO::getSessionId, sessionId)
                .eq(AgentCommandDO::getOwnerUserId, ownerUserId)
                .eq(AgentCommandDO::getCommandType, commandType)
                .eq(AgentCommandDO::getRequestId, requestId));
    }

    default AgentCommandDO selectActivePromptBySessionId(Long sessionId) {
        return selectOne(new LambdaQueryWrapperX<AgentCommandDO>()
                .eq(AgentCommandDO::getSessionId, sessionId)
                .eq(AgentCommandDO::getCommandType, CommandType.PROMPT.name())
                .in(AgentCommandDO::getCommandStatus, AgentCommandDbStatus.ACKED.name(),
                        AgentCommandDbStatus.RUNNING.name()));
    }
}
