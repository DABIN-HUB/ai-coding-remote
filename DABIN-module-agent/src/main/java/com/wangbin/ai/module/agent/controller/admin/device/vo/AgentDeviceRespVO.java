package com.wangbin.ai.module.agent.controller.admin.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - Agent 设备 Response VO")
@Data
public class AgentDeviceRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "设备业务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String deviceId;
    @Schema(description = "Daemon 安装实例编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String installationId;
    @Schema(description = "设备所属用户编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long ownerUserId;
    @Schema(description = "设备名称")
    private String deviceName;
    @Schema(description = "主机名")
    private String hostname;
    @Schema(description = "操作系统名称")
    private String osName;
    @Schema(description = "操作系统版本")
    private String osVersion;
    @Schema(description = "系统架构")
    private String osArch;
    @Schema(description = "Daemon 版本")
    private String daemonVersion;
    @Schema(description = "设备注册状态，ACTIVE=正常，DISABLED=禁用")
    private String deviceStatus;
    @Schema(description = "当前是否在线，来自 Redis Presence，不来自数据库")
    private Boolean online;
    @Schema(description = "最后在线时间，来自 Redis Presence")
    private Instant lastSeenAt;
    @Schema(description = "当前 Relay 节点编号，诊断字段")
    private String relayNodeId;
    @Schema(description = "Codex Runtime 状态，AVAILABLE=可用，UNAVAILABLE=不可用，DISABLED=禁用")
    private String runtimeStatus;
    @Schema(description = "Codex Runtime 当前是否可用，来自最近一次 Daemon runtime report")
    private Boolean runtimeAvailable;
    @Schema(description = "备注")
    private String remark;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
