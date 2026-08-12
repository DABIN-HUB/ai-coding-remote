package com.wangbin.ai.module.agent.controller.admin.permission;

import com.wangbin.ai.framework.common.pojo.CommonResult;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.module.agent.controller.admin.permission.vo.AgentPermissionDecideReqVO;
import com.wangbin.ai.module.agent.controller.admin.permission.vo.AgentPermissionPageReqVO;
import com.wangbin.ai.module.agent.controller.admin.permission.vo.AgentPermissionRespVO;
import com.wangbin.ai.module.agent.service.permission.AgentPermissionService;
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

@Tag(name = "管理后台 - Agent Permission")
@RestController
@RequestMapping("/agent/permission")
@Validated
@RequiredArgsConstructor
public class AgentPermissionController {

    private final AgentPermissionService permissionService;

    @GetMapping("/page")
    @Operation(summary = "获取当前用户 Permission 分页")
    @PreAuthorize("@ss.hasPermission('agent:permission:query')")
    public CommonResult<PageResult<AgentPermissionRespVO>> getPermissionPage(
            @Valid AgentPermissionPageReqVO reqVO) {
        return success(permissionService.getPermissionPage(reqVO, getLoginUserId()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取当前用户 Permission 详情")
    @Parameter(name = "permissionId", description = "权限请求业务编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:permission:query')")
    public CommonResult<AgentPermissionRespVO> getPermission(@RequestParam("permissionId") String permissionId) {
        return success(permissionService.getPermission(permissionId, getLoginUserId()));
    }

    @PostMapping("/decide")
    @Operation(summary = "审批当前用户 Permission")
    @PreAuthorize("@ss.hasPermission('agent:permission:decide')")
    public CommonResult<AgentPermissionRespVO> decidePermission(
            @Valid @RequestBody AgentPermissionDecideReqVO reqVO) {
        return success(permissionService.decidePermission(reqVO, getLoginUserId()));
    }
}
