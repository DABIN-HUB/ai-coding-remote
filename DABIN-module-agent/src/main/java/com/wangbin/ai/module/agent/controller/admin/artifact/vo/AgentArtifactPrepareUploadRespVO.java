package com.wangbin.ai.module.agent.controller.admin.artifact.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Daemon - Agent Artifact 准备上传 Response VO")
@Data
public class AgentArtifactPrepareUploadRespVO {

    @Schema(description = "Artifact 是否已经就绪")
    private Boolean alreadyReady;

    @Schema(description = "一次性上传 Ticket，仅返回一次，后续通过 Header 传输")
    private String uploadTicket;

    @Schema(description = "上传路径")
    private String uploadPath;
}
