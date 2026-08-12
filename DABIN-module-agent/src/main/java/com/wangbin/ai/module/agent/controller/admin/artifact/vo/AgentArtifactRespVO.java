package com.wangbin.ai.module.agent.controller.admin.artifact.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - Agent Artifact Response VO")
@Data
public class AgentArtifactRespVO {

    @Schema(description = "编号")
    private Long id;
    @Schema(description = "Artifact 业务编号")
    private String artifactId;
    @Schema(description = "来源类型")
    private String sourceType;
    @Schema(description = "状态")
    private String status;
    @Schema(description = "Session 表编号")
    private Long sessionId;
    @Schema(description = "源 Prompt Command 表编号")
    private Long sourceCommandId;
    @Schema(description = "Transfer Command 表编号")
    private Long transferCommandId;
    @Schema(description = "ChangeSet 表编号")
    private Long changeSetId;
    @Schema(description = "FileChange 表编号")
    private Long fileChangeId;
    @Schema(description = "Workspace 相对路径")
    private String relativePath;
    @Schema(description = "文件名")
    private String fileName;
    @Schema(description = "MIME 类型")
    private String contentType;
    @Schema(description = "文件大小")
    private Long fileSize;
    @Schema(description = "SHA-256")
    private String sha256;
    @Schema(description = "项目统一文件记录编号")
    private Long fileId;
    @Schema(description = "请求时间")
    private LocalDateTime requestedTime;
    @Schema(description = "就绪时间")
    private LocalDateTime readyTime;
    @Schema(description = "过期时间")
    private LocalDateTime expireTime;
    @Schema(description = "错误码")
    private String errorCode;
    @Schema(description = "失败摘要")
    private String errorMessage;
}
