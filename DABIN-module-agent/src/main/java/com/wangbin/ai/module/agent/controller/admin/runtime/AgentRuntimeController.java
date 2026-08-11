package com.wangbin.ai.module.agent.controller.admin.runtime;

import com.wangbin.ai.agent.contract.protocol.AgentHttpHeaders;
import com.wangbin.ai.framework.common.pojo.CommonResult;
import com.wangbin.ai.framework.web.core.util.WebFrameworkUtils;
import com.wangbin.ai.module.agent.controller.admin.runtime.vo.AgentRuntimeReportReqVO;
import com.wangbin.ai.module.agent.controller.admin.runtime.vo.AgentRuntimeRespVO;
import com.wangbin.ai.module.agent.service.runtime.AgentRuntimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.wangbin.ai.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - Agent Runtime")
@RestController
@RequestMapping("/agent/runtime")
@Validated
@RequiredArgsConstructor
public class AgentRuntimeController {

    private final AgentRuntimeService runtimeService;

    @PostMapping("/report")
    @Operation(summary = "Daemon 上报本地 Runtime 能力")
    @PermitAll
    public CommonResult<AgentRuntimeRespVO> reportRuntime(
            @Parameter(description = "设备凭证公开编号", required = true)
            @RequestHeader(AgentHttpHeaders.CREDENTIAL_ID) String credentialId,
            @Parameter(description = "设备凭证明文密钥", required = true)
            @RequestHeader(AgentHttpHeaders.CREDENTIAL_SECRET) String credentialSecret,
            @Parameter(description = "租户编号 Header，复用 RuoYi tenant-id", required = true)
            @RequestHeader(WebFrameworkUtils.HEADER_TENANT_ID) Long tenantId,
            @Valid @RequestBody AgentRuntimeReportReqVO reqVO) {
        return success(runtimeService.reportRuntime(tenantId, credentialId, credentialSecret, reqVO));
    }
}
