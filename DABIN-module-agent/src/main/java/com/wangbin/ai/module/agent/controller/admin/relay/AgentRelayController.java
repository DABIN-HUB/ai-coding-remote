package com.wangbin.ai.module.agent.controller.admin.relay;

import com.wangbin.ai.agent.contract.coordination.RelayTicketPayload;
import com.wangbin.ai.framework.common.pojo.CommonResult;
import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.module.agent.controller.admin.relay.vo.AgentUserRelayTicketRespVO;
import com.wangbin.ai.module.agent.service.relay.RelayTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.wangbin.ai.framework.common.pojo.CommonResult.success;
import static com.wangbin.ai.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - Agent Relay")
@RestController
@RequestMapping("/agent/relay")
@RequiredArgsConstructor
public class AgentRelayController {

    private final RelayTicketService relayTicketService;

    @PostMapping("/createUserTicket")
    @Operation(summary = "当前登录用户申请一次性 Relay Ticket")
    @PreAuthorize("@ss.hasPermission('agent:relay:createUserTicket')")
    public CommonResult<AgentUserRelayTicketRespVO> createUserTicket() {
        RelayTicketPayload ticket = relayTicketService.createUserTicket(TenantContextHolder.getRequiredTenantId(),
                getLoginUserId());
        return success(new AgentUserRelayTicketRespVO(ticket.ticket(), ticket.expireAt()));
    }
}
