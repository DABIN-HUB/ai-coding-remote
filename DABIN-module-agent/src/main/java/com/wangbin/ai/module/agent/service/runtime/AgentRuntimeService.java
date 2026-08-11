package com.wangbin.ai.module.agent.service.runtime;

import com.wangbin.ai.module.agent.controller.admin.runtime.vo.AgentRuntimeReportReqVO;
import com.wangbin.ai.module.agent.controller.admin.runtime.vo.AgentRuntimeRespVO;

public interface AgentRuntimeService {

    AgentRuntimeRespVO reportRuntime(Long tenantId, String credentialId, String credentialSecret,
                                     AgentRuntimeReportReqVO reqVO);
}
