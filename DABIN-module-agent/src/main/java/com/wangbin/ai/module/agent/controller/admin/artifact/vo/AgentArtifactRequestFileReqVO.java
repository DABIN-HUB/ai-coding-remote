package com.wangbin.ai.module.agent.controller.admin.artifact.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - Agent Artifact 文件请求 Request VO")
@Data
public class AgentArtifactRequestFileReqVO {

    @Schema(description = "FileChange 业务编号")
    @NotBlank
    private String fileChangeId;

    @Schema(description = "客户端幂等请求编号")
    @Size(max = 128)
    private String clientRequestId;
}
