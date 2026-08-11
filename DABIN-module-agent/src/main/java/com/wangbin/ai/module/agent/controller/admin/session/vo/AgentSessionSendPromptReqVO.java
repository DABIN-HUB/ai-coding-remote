package com.wangbin.ai.module.agent.controller.admin.session.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - Agent Session 发送 Prompt Request VO")
@Data
public class AgentSessionSendPromptReqVO {

    @Schema(description = "平台 Session 业务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 64)
    private String sessionId;

    @Schema(description = "Prompt 内容", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String content;

    @Schema(description = "客户端幂等请求编号，同一 session 内重复提交将返回已有 command")
    @Size(max = 64)
    private String clientRequestId;
}
