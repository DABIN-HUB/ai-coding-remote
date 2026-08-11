package com.wangbin.ai.module.agent.dal.mysql.project;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.mybatis.core.mapper.BaseMapperX;
import com.wangbin.ai.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.wangbin.ai.module.agent.controller.admin.project.vo.AgentProjectPageReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.project.AgentProjectDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentProjectMapper extends BaseMapperX<AgentProjectDO> {

    default AgentProjectDO selectByProjectId(String projectId) {
        return selectOne(AgentProjectDO::getProjectId, projectId);
    }

    default AgentProjectDO selectByLocalProject(Long deviceId, Long ownerUserId, String localProjectId) {
        return selectOne(new LambdaQueryWrapperX<AgentProjectDO>()
                .eq(AgentProjectDO::getDeviceId, deviceId)
                .eq(AgentProjectDO::getOwnerUserId, ownerUserId)
                .eq(AgentProjectDO::getLocalProjectId, localProjectId));
    }

    default PageResult<AgentProjectDO> selectPage(AgentProjectPageReqVO reqVO, Long ownerUserId) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentProjectDO>()
                .eq(AgentProjectDO::getOwnerUserId, ownerUserId)
                .eqIfPresent(AgentProjectDO::getDeviceId, reqVO.getDeviceDbId())
                .likeIfPresent(AgentProjectDO::getProjectName, reqVO.getProjectName())
                .eqIfPresent(AgentProjectDO::getProjectStatus, reqVO.getProjectStatus())
                .orderByDesc(AgentProjectDO::getId));
    }
}
