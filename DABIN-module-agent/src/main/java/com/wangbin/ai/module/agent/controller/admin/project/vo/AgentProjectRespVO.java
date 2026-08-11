package com.wangbin.ai.module.agent.controller.admin.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - Agent Project Response VO")
@Data
public class AgentProjectRespVO {

    @Schema(description = "编号")
    private Long id;
    @Schema(description = "设备表编号")
    private Long deviceId;
    @Schema(description = "项目业务编号")
    private String projectId;
    @Schema(description = "Daemon 本地项目稳定编号")
    private String localProjectId;
    @Schema(description = "所属用户编号")
    private Long ownerUserId;
    @Schema(description = "项目名称")
    private String projectName;
    @Schema(description = "Daemon 本地工作目录展示路径")
    private String workspacePath;
    @Schema(description = "Daemon 最近一次规范化后的真实路径")
    private String workspaceRealPath;
    @Schema(description = "默认 Agent 类型")
    private String agentType;
    @Schema(description = "项目状态，ACTIVE/DISABLED")
    private String projectStatus;
    @Schema(description = "最近确认存在时间")
    private LocalDateTime lastSeenTime;
}
