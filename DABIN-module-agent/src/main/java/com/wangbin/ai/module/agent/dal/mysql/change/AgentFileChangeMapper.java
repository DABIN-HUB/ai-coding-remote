package com.wangbin.ai.module.agent.dal.mysql.change;

import com.wangbin.ai.framework.mybatis.core.mapper.BaseMapperX;
import com.wangbin.ai.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.wangbin.ai.module.agent.dal.dataobject.change.AgentFileChangeDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentFileChangeMapper extends BaseMapperX<AgentFileChangeDO> {

    default List<AgentFileChangeDO> selectListByChangeSetId(Long changeSetId) {
        return selectList(new LambdaQueryWrapperX<AgentFileChangeDO>()
                .eq(AgentFileChangeDO::getChangeSetId, changeSetId)
                .orderByAsc(AgentFileChangeDO::getId));
    }

    default AgentFileChangeDO selectByFileChangeId(String fileChangeId) {
        return selectOne(AgentFileChangeDO::getFileChangeId, fileChangeId);
    }
}
