package com.wangbin.ai.module.agent.controller.admin.change.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - Agent ChangeSet Response VO")
@Data
public class AgentChangeSetRespVO {

    @Schema(description = "编号")
    private Long id;
    @Schema(description = "变更集业务编号")
    private String changeSetId;
    @Schema(description = "Session 表编号")
    private Long sessionId;
    @Schema(description = "Command 表编号")
    private Long commandId;
    @Schema(description = "Project 表编号")
    private Long projectId;
    @Schema(description = "变更集状态")
    private String status;
    @Schema(description = "变更文件数量")
    private Integer fileCount;
    @Schema(description = "新增行数")
    private Integer additions;
    @Schema(description = "删除行数")
    private Integer deletions;
    @Schema(description = "Diff 是否被截断")
    private Boolean diffTruncated;
    @Schema(description = "文件列表是否被截断")
    private Boolean filesTruncated;
    @Schema(description = "开始时间")
    private LocalDateTime startedTime;
    @Schema(description = "完成时间")
    private LocalDateTime completedTime;
}
