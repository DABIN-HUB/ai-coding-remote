package com.wangbin.ai.module.agent.controller.admin.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Daemon - Agent 设备绑定 Request VO")
@Data
public class AgentDevicePairReqVO {

    @Schema(description = "一次性绑定码，格式 XXXX-XXXX", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String pairingCode;

    @Schema(description = "Daemon 安装实例编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 64)
    private String installationId;

    @Schema(description = "设备名称")
    @Size(max = 128)
    private String deviceName;

    @Schema(description = "主机名")
    @Size(max = 128)
    private String hostname;

    @Schema(description = "操作系统名称")
    @Size(max = 64)
    private String osName;

    @Schema(description = "操作系统版本")
    @Size(max = 128)
    private String osVersion;

    @Schema(description = "系统架构")
    @Size(max = 64)
    private String osArch;

    @Schema(description = "Daemon 版本")
    @Size(max = 64)
    private String daemonVersion;
}
