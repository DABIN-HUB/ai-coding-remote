package com.wangbin.ai.module.agent.controller.admin.permission.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - Agent Permission Response VO")
@Data
public class AgentPermissionRespVO {

    @Schema(description = "编号")
    private Long id;
    @Schema(description = "权限请求业务编号")
    private String permissionId;
    @Schema(description = "Session 表编号")
    private Long sessionId;
    @Schema(description = "触发权限的 Prompt Command 表编号")
    private Long commandId;
    @Schema(description = "设备表编号")
    private Long deviceId;
    @Schema(description = "项目表编号")
    private Long projectId;
    @Schema(description = "所属用户编号")
    private Long ownerUserId;
    @Schema(description = "权限类型")
    private String permissionType;
    @Schema(description = "权限请求状态")
    private String permissionStatus;
    @Schema(description = "权限请求标题")
    private String title;
    @Schema(description = "权限请求原因")
    private String reason;
    @Schema(description = "平台脱敏后的权限详情 JSON")
    private String requestJson;
    @Schema(description = "用户决策")
    private String decision;
    @Schema(description = "用户决策说明")
    private String decisionReason;
    @Schema(description = "执行审批的用户编号")
    private Long decisionUserId;
    @Schema(description = "权限决策 Command 表编号")
    private Long decisionCommandId;
    @Schema(description = "请求时间")
    private LocalDateTime requestedTime;
    @Schema(description = "用户决策时间")
    private LocalDateTime decidedTime;
    @Schema(description = "Codex 确认结束时间")
    private LocalDateTime resolvedTime;
    @Schema(description = "失败摘要")
    private String errorMessage;
}
