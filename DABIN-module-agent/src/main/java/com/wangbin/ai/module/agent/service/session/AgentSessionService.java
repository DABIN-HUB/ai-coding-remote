package com.wangbin.ai.module.agent.service.session;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.module.agent.controller.admin.message.vo.AgentMessagePageReqVO;
import com.wangbin.ai.module.agent.controller.admin.message.vo.AgentMessageRespVO;
import com.wangbin.ai.module.agent.controller.admin.session.vo.*;

import java.time.LocalDateTime;

public interface AgentSessionService {

    AgentSessionRespVO createSession(AgentSessionCreateReqVO reqVO, Long userId);

    PageResult<AgentSessionRespVO> getSessionPage(AgentSessionPageReqVO reqVO, Long userId);

    AgentSessionRespVO getSession(String sessionId, Long userId);

    AgentCommandRespVO sendPrompt(AgentSessionSendPromptReqVO reqVO, Long userId);

    AgentSessionControlRespVO interruptSession(AgentSessionInterruptReqVO reqVO, Long userId);

    AgentSessionControlRespVO cancelSessionCommand(AgentSessionCancelReqVO reqVO, Long userId);

    AgentSessionControlRespVO closeSession(AgentSessionCloseReqVO reqVO, Long userId);

    PageResult<AgentMessageRespVO> getMessagePage(String sessionId, AgentMessagePageReqVO reqVO, Long userId);

    void markAckTimeout(String commandId, LocalDateTime now);
}
