package com.wangbin.ai.module.agent.dal.mysql.runtime;

import com.wangbin.ai.framework.mybatis.core.mapper.BaseMapperX;
import com.wangbin.ai.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.wangbin.ai.module.agent.dal.dataobject.runtime.AgentRuntimeDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentRuntimeMapper extends BaseMapperX<AgentRuntimeDO> {

    default AgentRuntimeDO selectByRuntimeId(String runtimeId) {
        return selectOne(AgentRuntimeDO::getRuntimeId, runtimeId);
    }

    default AgentRuntimeDO selectByDeviceAndType(Long deviceId, String runtimeType) {
        return selectOne(new LambdaQueryWrapperX<AgentRuntimeDO>()
                .eq(AgentRuntimeDO::getDeviceId, deviceId)
                .eq(AgentRuntimeDO::getRuntimeType, runtimeType));
    }
}
