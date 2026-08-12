package com.wangbin.ai.module.agent.service.permission;

import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.PermissionRequiredPayload;
import com.wangbin.ai.agent.contract.event.PermissionResolvedPayload;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.module.agent.controller.admin.permission.vo.AgentPermissionDecideReqVO;
import com.wangbin.ai.module.agent.controller.admin.permission.vo.AgentPermissionPageReqVO;
import com.wangbin.ai.module.agent.controller.admin.permission.vo.AgentPermissionRespVO;
import com.wangbin.ai.module.agent.dal.dataobject.command.AgentCommandDO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;

public interface AgentPermissionService {

    PageResult<AgentPermissionRespVO> getPermissionPage(AgentPermissionPageReqVO reqVO, Long userId);

    AgentPermissionRespVO getPermission(String permissionId, Long userId);

    AgentPermissionRespVO decidePermission(AgentPermissionDecideReqVO reqVO, Long userId);

    void handlePermissionRequired(AgentSessionDO session, AgentEvent event, String platformCommandId,
                                  PermissionRequiredPayload payload);

    void handlePermissionResolved(PermissionResolvedPayload payload);

    void handleDecisionCommandAck(AgentCommandDO command, CommandAck ack);
}
