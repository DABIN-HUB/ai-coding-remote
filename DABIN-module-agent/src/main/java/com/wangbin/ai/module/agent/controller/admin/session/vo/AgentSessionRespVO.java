package com.wangbin.ai.module.agent.controller.admin.session.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - Agent Session Response VO")
@Data
public class AgentSessionRespVO {

    @Schema(description = "编号")
    private Long id;
    @Schema(description = "平台 Session 业务编号")
    private String sessionId;
    @Schema(description = "设备表编号")
    private Long deviceId;
    @Schema(description = "项目表编号")
    private Long projectId;
    @Schema(description = "Runtime 表编号")
    private Long runtimeId;
    @Schema(description = "所属用户编号")
    private Long ownerUserId;
    @Schema(description = "Agent 类型")
    private String agentType;
    @Schema(description = "本地 Agent Native Session，仅诊断")
    private String nativeSessionId;
    @Schema(description = "Session 状态")
    private String sessionStatus;
    @Schema(description = "已接受最大连续 AgentEvent seq")
    private Long lastEventSeq;
    @Schema(description = "最后活跃时间")
    private LocalDateTime lastActiveTime;
    @Schema(description = "开始时间")
    private LocalDateTime startedTime;
    @Schema(description = "关闭时间")
    private LocalDateTime closedTime;
    @Schema(description = "可公开错误摘要")
    private String errorMessage;
}
