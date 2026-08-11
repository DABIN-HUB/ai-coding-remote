package com.wangbin.ai.module.agent.controller.admin.device;

import com.wangbin.ai.framework.common.pojo.CommonResult;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.framework.web.core.util.WebFrameworkUtils;
import com.wangbin.ai.module.agent.controller.admin.device.vo.*;
import com.wangbin.ai.module.agent.enums.AgentHttpHeaders;
import com.wangbin.ai.module.agent.service.device.AgentDeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.wangbin.ai.framework.common.pojo.CommonResult.success;
import static com.wangbin.ai.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - Agent 设备")
@RestController
@RequestMapping("/agent/device")
@Validated
@RequiredArgsConstructor
public class AgentDeviceController {

    private final AgentDeviceService deviceService;

    @PostMapping("/createPairingCode")
    @Operation(summary = "创建一次性设备绑定码")
    @PreAuthorize("@ss.hasPermission('agent:device:create')")
    public CommonResult<AgentPairingCodeRespVO> createPairingCode() {
        return success(deviceService.createPairingCode(TenantContextHolder.getRequiredTenantId(), getLoginUserId()));
    }

    @GetMapping("/page")
    @Operation(summary = "获取当前用户设备分页")
    @PreAuthorize("@ss.hasPermission('agent:device:query')")
    public CommonResult<PageResult<AgentDeviceRespVO>> getDevicePage(@Valid AgentDevicePageReqVO reqVO) {
        return success(deviceService.getDevicePage(reqVO, getLoginUserId()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取当前用户设备详情")
    @Parameter(name = "id", description = "设备表编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:device:query')")
    public CommonResult<AgentDeviceRespVO> getDevice(@RequestParam("id") Long id) {
        return success(deviceService.getDevice(id, getLoginUserId()));
    }

    @PostMapping("/pair")
    @Operation(summary = "Daemon 使用一次性绑定码绑定设备")
    @PermitAll
    public CommonResult<AgentDevicePairRespVO> pair(@Valid @RequestBody AgentDevicePairReqVO reqVO) {
        return success(deviceService.pairDevice(reqVO));
    }

    @PostMapping("/createRelayTicket")
    @Operation(summary = "Daemon 使用设备凭证申请一次性 Relay Ticket")
    @PermitAll
    public CommonResult<AgentDeviceRelayTicketRespVO> createRelayTicket(
            @Parameter(description = "设备凭证公开编号", required = true)
            @RequestHeader(AgentHttpHeaders.CREDENTIAL_ID) String credentialId,
            @Parameter(description = "设备凭证明文密钥", required = true)
            @RequestHeader(AgentHttpHeaders.CREDENTIAL_SECRET) String credentialSecret,
            @Parameter(description = "租户编号 Header，复用 RuoYi tenant-id", required = true)
            @RequestHeader(WebFrameworkUtils.HEADER_TENANT_ID) Long tenantId) {
        return success(deviceService.createDeviceRelayTicket(tenantId, credentialId, credentialSecret));
    }
}
