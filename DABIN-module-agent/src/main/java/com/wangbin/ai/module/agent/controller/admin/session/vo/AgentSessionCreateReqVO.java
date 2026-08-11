package com.wangbin.ai.module.agent.controller.admin.session.vo;

import com.wangbin.ai.agent.contract.enums.AgentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - Agent Session 创建 Request VO")
@Data
public class AgentSessionCreateReqVO {

    @Schema(description = "项目表编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long projectId;

    @Schema(description = "Agent 类型", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private AgentType agentType;
}
