package com.wangbin.ai.module.agent.service.project;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.module.agent.controller.admin.project.vo.AgentProjectPageReqVO;
import com.wangbin.ai.module.agent.controller.admin.project.vo.AgentProjectRegisterReqVO;
import com.wangbin.ai.module.agent.controller.admin.project.vo.AgentProjectRespVO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.dataobject.project.AgentProjectDO;
import com.wangbin.ai.module.agent.dal.mysql.project.AgentProjectMapper;
import com.wangbin.ai.module.agent.enums.ProjectStatus;
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import com.wangbin.ai.module.agent.service.device.DeviceCredentialAuthService;
import com.wangbin.ai.module.agent.service.device.DeviceCredentialIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.*;

@Service
@RequiredArgsConstructor
public class AgentProjectServiceImpl implements AgentProjectService {

    private final AgentProjectMapper projectMapper;
    private final DeviceCredentialAuthService credentialAuthService;
    private final AgentIdFactory idFactory;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentProjectRespVO registerProject(Long tenantId, String credentialId, String credentialSecret,
                                              AgentProjectRegisterReqVO reqVO) {
        DeviceCredentialIdentity identity = credentialAuthService.authenticate(tenantId, credentialId, credentialSecret);
        AgentDeviceDO device = identity.device();
        AgentProjectDO project = projectMapper.selectByLocalProject(device.getId(), identity.ownerUserId(),
                reqVO.getLocalProjectId());
        if (project == null) {
            project = new AgentProjectDO();
            project.setTenantId(identity.tenantId());
            project.setDeviceId(device.getId());
            project.setProjectId(idFactory.projectId());
            project.setLocalProjectId(reqVO.getLocalProjectId());
            project.setOwnerUserId(identity.ownerUserId());
            project.setProjectStatus(ProjectStatus.ACTIVE.name());
            project.setCreator(String.valueOf(identity.ownerUserId()));
        }
        project.setProjectName(reqVO.getProjectName());
        project.setWorkspacePath(reqVO.getWorkspacePath());
        project.setWorkspaceRealPath(reqVO.getWorkspaceRealPath());
        project.setAgentType(reqVO.getAgentType().name());
        project.setLastSeenTime(LocalDateTime.now());
        project.setUpdater(String.valueOf(identity.ownerUserId()));
        if (project.getId() == null) {
            projectMapper.insert(project);
        } else {
            projectMapper.updateById(project);
        }
        return toRespVO(project);
    }

    @Override
    public PageResult<AgentProjectRespVO> getProjectPage(AgentProjectPageReqVO reqVO, Long userId) {
        PageResult<AgentProjectDO> page = projectMapper.selectPage(reqVO, userId);
        return new PageResult<>(page.getList().stream().map(this::toRespVO).toList(), page.getTotal());
    }

    @Override
    public AgentProjectRespVO getProject(Long id, Long userId) {
        AgentProjectDO project = projectMapper.selectById(id);
        checkOwner(project, userId);
        return toRespVO(project);
    }

    private void checkOwner(AgentProjectDO project, Long userId) {
        if (project == null) {
            throw exception(PROJECT_NOT_EXISTS);
        }
        if (!project.getOwnerUserId().equals(userId)) {
            throw exception(PROJECT_ACCESS_DENIED);
        }
    }

    private AgentProjectRespVO toRespVO(AgentProjectDO project) {
        AgentProjectRespVO respVO = new AgentProjectRespVO();
        respVO.setId(project.getId());
        respVO.setDeviceId(project.getDeviceId());
        respVO.setProjectId(project.getProjectId());
        respVO.setLocalProjectId(project.getLocalProjectId());
        respVO.setOwnerUserId(project.getOwnerUserId());
        respVO.setProjectName(project.getProjectName());
        respVO.setWorkspacePath(project.getWorkspacePath());
        respVO.setWorkspaceRealPath(project.getWorkspaceRealPath());
        respVO.setAgentType(project.getAgentType());
        respVO.setProjectStatus(project.getProjectStatus());
        respVO.setLastSeenTime(project.getLastSeenTime());
        return respVO;
    }
}
