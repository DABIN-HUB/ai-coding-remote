package com.wangbin.ai.module.agent.controller.admin.runtime.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - Agent Runtime Response VO")
@Data
public class AgentRuntimeRespVO {

    @Schema(description = "编号")
    private Long id;
    @Schema(description = "设备表编号")
    private Long deviceId;
    @Schema(description = "Runtime 业务编号")
    private String runtimeId;
    @Schema(description = "运行时类型")
    private String runtimeType;
    @Schema(description = "运行时版本")
    private String runtimeVersion;
    @Schema(description = "本地 executable 标识或路径，仅用于诊断")
    private String executablePath;
    @Schema(description = "运行时状态，AVAILABLE/UNAVAILABLE/DISABLED")
    private String runtimeStatus;
    @Schema(description = "平台 AgentCapabilities JSON 快照")
    private String capabilitiesJson;
    @Schema(description = "最近发现时间")
    private LocalDateTime lastDiscoveredTime;
}
