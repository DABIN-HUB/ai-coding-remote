package com.wangbin.ai.module.agent.controller.admin.runtime.vo;

import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.session.AgentCapabilities;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - Agent Runtime 上报 Request VO，由已认证 Daemon 调用")
@Data
public class AgentRuntimeReportReqVO {

    @Schema(description = "运行时业务编号，可为空由服务端生成")
    @Size(max = 64)
    private String runtimeId;

    @Schema(description = "Agent 类型", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private AgentType agentType;

    @Schema(description = "运行时类型，例如 CODEX_APP_SERVER", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 32)
    private String runtimeType;

    @Schema(description = "运行时版本")
    @Size(max = 64)
    private String runtimeVersion;

    @Schema(description = "本地 executable 标识或路径，仅用于诊断")
    @Size(max = 1024)
    private String executablePath;

    @Schema(description = "平台能力快照，只允许 AgentCapabilities，不保存 Native 协议对象")
    @NotNull
    private AgentCapabilities capabilities;
}
