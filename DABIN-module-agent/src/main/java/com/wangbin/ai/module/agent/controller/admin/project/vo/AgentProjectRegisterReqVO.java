package com.wangbin.ai.module.agent.controller.admin.project.vo;

import com.wangbin.ai.agent.contract.enums.AgentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - Agent Project 注册 Request VO，由已认证 Daemon 调用")
@Data
public class AgentProjectRegisterReqVO {

    @Schema(description = "Daemon 本地项目稳定编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String localProjectId;

    @Schema(description = "项目名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String projectName;

    @Schema(description = "Daemon 本地工作目录展示路径，执行前仍由 Daemon 校验", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 1024)
    private String workspacePath;

    @Schema(description = "Daemon 最近一次规范化后的真实路径")
    @Size(max = 1024)
    private String workspaceRealPath;

    @Schema(description = "默认 Agent 类型，固定协议枚举", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private AgentType agentType;
}
