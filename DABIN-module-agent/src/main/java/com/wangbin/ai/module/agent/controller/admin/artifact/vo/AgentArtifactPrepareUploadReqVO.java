package com.wangbin.ai.module.agent.controller.admin.artifact.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Daemon - Agent Artifact 准备上传 Request VO")
@Data
public class AgentArtifactPrepareUploadReqVO {

    @Schema(description = "Artifact 业务编号")
    @NotBlank
    private String artifactId;

    @Schema(description = "文件大小")
    @Min(0)
    private long fileSize;

    @Schema(description = "文件 SHA-256")
    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{64}$")
    private String sha256;

    @Schema(description = "MIME 类型")
    @Size(max = 128)
    private String contentType;

    @Schema(description = "源文件最后修改时间")
    private LocalDateTime sourceLastModifiedTime;
}
