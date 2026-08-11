package com.wangbin.ai.module.agent.controller.admin.project;

import com.wangbin.ai.agent.contract.protocol.AgentHttpHeaders;
import com.wangbin.ai.framework.common.pojo.CommonResult;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.web.core.util.WebFrameworkUtils;
import com.wangbin.ai.module.agent.controller.admin.project.vo.AgentProjectPageReqVO;
import com.wangbin.ai.module.agent.controller.admin.project.vo.AgentProjectRegisterReqVO;
import com.wangbin.ai.module.agent.controller.admin.project.vo.AgentProjectRespVO;
import com.wangbin.ai.module.agent.service.project.AgentProjectService;
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

@Tag(name = "管理后台 - Agent Project")
@RestController
@RequestMapping("/agent/project")
@Validated
@RequiredArgsConstructor
public class AgentProjectController {

    private final AgentProjectService projectService;

    @PostMapping("/register")
    @Operation(summary = "Daemon 注册或刷新本地 Project")
    @PermitAll
    public CommonResult<AgentProjectRespVO> registerProject(
            @Parameter(description = "设备凭证公开编号", required = true)
            @RequestHeader(AgentHttpHeaders.CREDENTIAL_ID) String credentialId,
            @Parameter(description = "设备凭证明文密钥", required = true)
            @RequestHeader(AgentHttpHeaders.CREDENTIAL_SECRET) String credentialSecret,
            @Parameter(description = "租户编号 Header，复用 RuoYi tenant-id", required = true)
            @RequestHeader(WebFrameworkUtils.HEADER_TENANT_ID) Long tenantId,
            @Valid @RequestBody AgentProjectRegisterReqVO reqVO) {
        return success(projectService.registerProject(tenantId, credentialId, credentialSecret, reqVO));
    }

    @GetMapping("/page")
    @Operation(summary = "获取当前用户 Project 分页")
    @PreAuthorize("@ss.hasPermission('agent:project:query')")
    public CommonResult<PageResult<AgentProjectRespVO>> getProjectPage(@Valid AgentProjectPageReqVO reqVO) {
        return success(projectService.getProjectPage(reqVO, getLoginUserId()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取当前用户 Project 详情")
    @Parameter(name = "id", description = "Project 表编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:project:query')")
    public CommonResult<AgentProjectRespVO> getProject(@RequestParam("id") Long id) {
        return success(projectService.getProject(id, getLoginUserId()));
    }
}
