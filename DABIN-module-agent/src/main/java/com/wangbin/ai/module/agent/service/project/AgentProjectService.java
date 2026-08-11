package com.wangbin.ai.module.agent.service.project;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.module.agent.controller.admin.project.vo.AgentProjectPageReqVO;
import com.wangbin.ai.module.agent.controller.admin.project.vo.AgentProjectRegisterReqVO;
import com.wangbin.ai.module.agent.controller.admin.project.vo.AgentProjectRespVO;

public interface AgentProjectService {

    AgentProjectRespVO registerProject(Long tenantId, String credentialId, String credentialSecret,
                                       AgentProjectRegisterReqVO reqVO);

    PageResult<AgentProjectRespVO> getProjectPage(AgentProjectPageReqVO reqVO, Long userId);

    AgentProjectRespVO getProject(Long id, Long userId);
}
