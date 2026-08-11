package com.wangbin.ai.module.agent.controller.admin.session;

import com.wangbin.ai.framework.common.pojo.CommonResult;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.module.agent.controller.admin.message.vo.AgentMessagePageReqVO;
import com.wangbin.ai.module.agent.controller.admin.message.vo.AgentMessageRespVO;
import com.wangbin.ai.module.agent.controller.admin.session.vo.*;
import com.wangbin.ai.module.agent.service.session.AgentSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.wangbin.ai.framework.common.pojo.CommonResult.success;
import static com.wangbin.ai.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - Agent Session")
@RestController
@RequestMapping("/agent/session")
@Validated
@RequiredArgsConstructor
public class AgentSessionController {

    private final AgentSessionService sessionService;

    @PostMapping("/create")
    @Operation(summary = "创建 Agent Session")
    @PreAuthorize("@ss.hasPermission('agent:session:create')")
    public CommonResult<AgentSessionRespVO> createSession(@Valid @RequestBody AgentSessionCreateReqVO reqVO) {
        return success(sessionService.createSession(reqVO, getLoginUserId()));
    }

    @GetMapping("/page")
    @Operation(summary = "获取当前用户 Session 分页")
    @PreAuthorize("@ss.hasPermission('agent:session:query')")
    public CommonResult<PageResult<AgentSessionRespVO>> getSessionPage(@Valid AgentSessionPageReqVO reqVO) {
        return success(sessionService.getSessionPage(reqVO, getLoginUserId()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取当前用户 Session 详情")
    @Parameter(name = "sessionId", description = "平台 Session 业务编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:session:query')")
    public CommonResult<AgentSessionRespVO> getSession(@RequestParam("sessionId") String sessionId) {
        return success(sessionService.getSession(sessionId, getLoginUserId()));
    }

    @PostMapping("/sendPrompt")
    @Operation(summary = "向 Agent Session 发送 Prompt")
    @PreAuthorize("@ss.hasPermission('agent:session:create')")
    public CommonResult<AgentCommandRespVO> sendPrompt(@Valid @RequestBody AgentSessionSendPromptReqVO reqVO) {
        return success(sessionService.sendPrompt(reqVO, getLoginUserId()));
    }

    @GetMapping("/messagePage")
    @Operation(summary = "获取 Session 最终消息分页")
    @Parameter(name = "sessionId", description = "平台 Session 业务编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:session:query')")
    public CommonResult<PageResult<AgentMessageRespVO>> getMessagePage(@RequestParam("sessionId") String sessionId,
                                                                       @Valid AgentMessagePageReqVO reqVO) {
        return success(sessionService.getMessagePage(sessionId, reqVO, getLoginUserId()));
    }
}
