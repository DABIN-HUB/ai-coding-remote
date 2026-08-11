package com.wangbin.ai.module.agent.dal.mysql.session;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.mybatis.core.mapper.BaseMapperX;
import com.wangbin.ai.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.wangbin.ai.module.agent.controller.admin.session.vo.AgentSessionPageReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentSessionMapper extends BaseMapperX<AgentSessionDO> {

    default AgentSessionDO selectBySessionId(String sessionId) {
        return selectOne(AgentSessionDO::getSessionId, sessionId);
    }

    default PageResult<AgentSessionDO> selectPage(AgentSessionPageReqVO reqVO, Long ownerUserId) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentSessionDO>()
                .eq(AgentSessionDO::getOwnerUserId, ownerUserId)
                .eqIfPresent(AgentSessionDO::getProjectId, reqVO.getProjectDbId())
                .eqIfPresent(AgentSessionDO::getSessionStatus, reqVO.getSessionStatus())
                .orderByDesc(AgentSessionDO::getId));
    }
}
