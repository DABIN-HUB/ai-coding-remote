package com.wangbin.ai.module.agent.dal.mysql.device;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.mybatis.core.mapper.BaseMapperX;
import com.wangbin.ai.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.wangbin.ai.module.agent.controller.admin.device.vo.AgentDevicePageReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentDeviceMapper extends BaseMapperX<AgentDeviceDO> {

    default PageResult<AgentDeviceDO> selectPage(AgentDevicePageReqVO reqVO, Long ownerUserId) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentDeviceDO>()
                .eq(AgentDeviceDO::getOwnerUserId, ownerUserId)
                .likeIfPresent(AgentDeviceDO::getDeviceName, reqVO.getDeviceName())
                .eqIfPresent(AgentDeviceDO::getDeviceStatus, reqVO.getDeviceStatus())
                .orderByDesc(AgentDeviceDO::getId));
    }

    default AgentDeviceDO selectByDeviceId(String deviceId) {
        return selectOne(AgentDeviceDO::getDeviceId, deviceId);
    }

    default List<AgentDeviceDO> selectListByInstallation(Long ownerUserId, String installationId) {
        return selectList(new LambdaQueryWrapperX<AgentDeviceDO>()
                .eq(AgentDeviceDO::getOwnerUserId, ownerUserId)
                .eq(AgentDeviceDO::getInstallationId, installationId)
                .orderByDesc(AgentDeviceDO::getId));
    }
}
