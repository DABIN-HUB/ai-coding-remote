package com.wangbin.ai.module.agent.controller.admin.session.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - Agent Session Cancel Request VO")
@Data
public class AgentSessionCancelReqVO {

    @Schema(description = "平台 Session 业务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 64)
    private String sessionId;

    @Schema(description = "需要取消的 Prompt Command 业务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 64)
    private String targetCommandId;

    @Schema(description = "客户端幂等请求编号")
    @Size(max = 64)
    private String clientRequestId;

    @Schema(description = "取消原因")
    @Size(max = 512)
    private String reason;
}
