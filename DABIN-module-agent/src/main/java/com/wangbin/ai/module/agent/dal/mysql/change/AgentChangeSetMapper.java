package com.wangbin.ai.module.agent.dal.mysql.change;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.mybatis.core.mapper.BaseMapperX;
import com.wangbin.ai.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.wangbin.ai.module.agent.controller.admin.change.vo.AgentChangeSetPageReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.change.AgentChangeSetDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentChangeSetMapper extends BaseMapperX<AgentChangeSetDO> {

    default AgentChangeSetDO selectByChangeSetId(String changeSetId) {
        return selectOne(AgentChangeSetDO::getChangeSetId, changeSetId);
    }

    default AgentChangeSetDO selectByCommandId(Long commandId) {
        return selectOne(AgentChangeSetDO::getCommandId, commandId);
    }

    default PageResult<AgentChangeSetDO> selectPage(AgentChangeSetPageReqVO reqVO, Long ownerUserId,
                                                    Long sessionDbId, Long projectDbId, Long commandDbId) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentChangeSetDO>()
                .eq(AgentChangeSetDO::getOwnerUserId, ownerUserId)
                .eqIfPresent(AgentChangeSetDO::getSessionId, sessionDbId)
                .eqIfPresent(AgentChangeSetDO::getProjectId, projectDbId)
                .eqIfPresent(AgentChangeSetDO::getCommandId, commandDbId)
                .eqIfPresent(AgentChangeSetDO::getChangeStatus, reqVO.getChangeStatus())
                .orderByDesc(AgentChangeSetDO::getId));
    }
}
