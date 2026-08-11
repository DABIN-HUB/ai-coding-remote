package com.wangbin.ai.module.agent.controller.admin.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Schema(description = "管理后台 - Agent 设备绑定码 Response VO")
@Data
@AllArgsConstructor
public class AgentPairingCodeRespVO {

    @Schema(description = "一次性绑定码，格式 XXXX-XXXX", requiredMode = Schema.RequiredMode.REQUIRED)
    private String pairingCode;

    @Schema(description = "过期时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant expireAt;
}
