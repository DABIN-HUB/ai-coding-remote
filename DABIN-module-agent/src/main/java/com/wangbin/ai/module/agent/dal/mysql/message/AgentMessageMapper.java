package com.wangbin.ai.module.agent.dal.mysql.message;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.mybatis.core.mapper.BaseMapperX;
import com.wangbin.ai.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.wangbin.ai.module.agent.controller.admin.message.vo.AgentMessagePageReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.message.AgentMessageDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentMessageMapper extends BaseMapperX<AgentMessageDO> {

    default AgentMessageDO selectByMessageId(String messageId) {
        return selectOne(AgentMessageDO::getMessageId, messageId);
    }

    default PageResult<AgentMessageDO> selectPage(AgentMessagePageReqVO reqVO, Long sessionId) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentMessageDO>()
                .eq(AgentMessageDO::getSessionId, sessionId)
                .orderByAsc(AgentMessageDO::getId));
    }
}
