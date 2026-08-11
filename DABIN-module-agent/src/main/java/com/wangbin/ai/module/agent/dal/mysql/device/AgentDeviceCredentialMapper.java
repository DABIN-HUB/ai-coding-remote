package com.wangbin.ai.module.agent.dal.mysql.device;

import com.wangbin.ai.framework.mybatis.core.mapper.BaseMapperX;
import com.wangbin.ai.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceCredentialDO;
import com.wangbin.ai.module.agent.enums.CredentialStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentDeviceCredentialMapper extends BaseMapperX<AgentDeviceCredentialDO> {

    default AgentDeviceCredentialDO selectByCredentialId(String credentialId) {
        return selectOne(AgentDeviceCredentialDO::getCredentialId, credentialId);
    }

    default List<AgentDeviceCredentialDO> selectActiveListByDeviceId(Long deviceId) {
        return selectList(new LambdaQueryWrapperX<AgentDeviceCredentialDO>()
                .eq(AgentDeviceCredentialDO::getDeviceId, deviceId)
                .eq(AgentDeviceCredentialDO::getCredentialStatus, CredentialStatus.ACTIVE.name()));
    }
}
