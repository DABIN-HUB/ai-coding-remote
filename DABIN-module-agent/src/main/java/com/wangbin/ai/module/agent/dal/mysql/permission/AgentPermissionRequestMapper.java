package com.wangbin.ai.module.agent.dal.mysql.permission;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.mybatis.core.mapper.BaseMapperX;
import com.wangbin.ai.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.wangbin.ai.module.agent.controller.admin.permission.vo.AgentPermissionPageReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.permission.AgentPermissionRequestDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentPermissionRequestMapper extends BaseMapperX<AgentPermissionRequestDO> {

    default AgentPermissionRequestDO selectByPermissionId(String permissionId) {
        return selectOne(AgentPermissionRequestDO::getPermissionId, permissionId);
    }

    default AgentPermissionRequestDO selectByDecisionCommandId(Long decisionCommandId) {
        return selectOne(AgentPermissionRequestDO::getDecisionCommandId, decisionCommandId);
    }

    default PageResult<AgentPermissionRequestDO> selectPage(AgentPermissionPageReqVO reqVO, Long ownerUserId,
                                                           Long sessionDbId) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentPermissionRequestDO>()
                .eq(AgentPermissionRequestDO::getOwnerUserId, ownerUserId)
                .eqIfPresent(AgentPermissionRequestDO::getSessionId, sessionDbId)
                .eqIfPresent(AgentPermissionRequestDO::getPermissionStatus, reqVO.getPermissionStatus())
                .eqIfPresent(AgentPermissionRequestDO::getPermissionType, reqVO.getPermissionType())
                .orderByDesc(AgentPermissionRequestDO::getId));
    }
}
