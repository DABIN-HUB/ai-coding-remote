package com.wangbin.ai.module.agent.controller.admin.permission.vo;

import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - Agent Permission 决策 Request VO")
@Data
public class AgentPermissionDecideReqVO {

    @Schema(description = "权限请求业务编号")
    @NotBlank
    private String permissionId;

    @Schema(description = "用户审批决策")
    @NotNull
    private PermissionDecision decision;

    @Schema(description = "用户审批说明")
    @Size(max = 512)
    private String reason;
}
