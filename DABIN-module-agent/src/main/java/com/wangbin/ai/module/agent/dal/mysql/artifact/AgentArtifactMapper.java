package com.wangbin.ai.module.agent.dal.mysql.artifact;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.mybatis.core.mapper.BaseMapperX;
import com.wangbin.ai.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPageReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.artifact.AgentArtifactDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentArtifactMapper extends BaseMapperX<AgentArtifactDO> {

    default AgentArtifactDO selectByArtifactId(String artifactId) {
        return selectOne(AgentArtifactDO::getArtifactId, artifactId);
    }

    default AgentArtifactDO selectByClientRequestId(Long ownerUserId, Long fileChangeId, String clientRequestId) {
        return selectOne(new LambdaQueryWrapperX<AgentArtifactDO>()
                .eq(AgentArtifactDO::getOwnerUserId, ownerUserId)
                .eq(AgentArtifactDO::getFileChangeId, fileChangeId)
                .eq(AgentArtifactDO::getClientRequestId, clientRequestId)
                .orderByDesc(AgentArtifactDO::getId));
    }

    default PageResult<AgentArtifactDO> selectPage(AgentArtifactPageReqVO reqVO, Long ownerUserId,
                                                   Long sessionDbId, Long projectDbId) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentArtifactDO>()
                .eq(AgentArtifactDO::getOwnerUserId, ownerUserId)
                .eqIfPresent(AgentArtifactDO::getSessionId, sessionDbId)
                .eqIfPresent(AgentArtifactDO::getProjectId, projectDbId)
                .eqIfPresent(AgentArtifactDO::getArtifactStatus, reqVO.getArtifactStatus())
                .eqIfPresent(AgentArtifactDO::getArtifactSourceType, reqVO.getSourceType())
                .orderByDesc(AgentArtifactDO::getId));
    }

    default List<AgentArtifactDO> selectExpiredReady(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<AgentArtifactDO>()
                .eq(AgentArtifactDO::getArtifactStatus, com.wangbin.ai.agent.contract.enums.ArtifactStatus.READY.name())
                .lt(AgentArtifactDO::getExpireTime, now)
                .orderByAsc(AgentArtifactDO::getId)
                .last("LIMIT " + limit));
    }
}
