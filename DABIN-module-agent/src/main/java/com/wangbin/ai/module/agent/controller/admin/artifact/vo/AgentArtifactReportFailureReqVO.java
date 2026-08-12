package com.wangbin.ai.module.agent.controller.admin.artifact.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Daemon - Agent Artifact 失败上报 Request VO")
@Data
public class AgentArtifactReportFailureReqVO {

    @Schema(description = "Artifact 业务编号")
    @NotBlank
    private String artifactId;

    @Schema(description = "错误码")
    @NotBlank
    @Size(max = 64)
    private String errorCode;

    @Schema(description = "失败摘要")
    @Size(max = 1024)
    private String errorMessage;
}
