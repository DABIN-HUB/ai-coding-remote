package com.wangbin.ai.module.agent.controller.admin.relay.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Schema(description = "管理后台 - User Relay Ticket Response VO")
@Data
@AllArgsConstructor
public class AgentUserRelayTicketRespVO {

    @Schema(description = "一次性短期 Relay Ticket", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ticket;

    @Schema(description = "过期时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant expireAt;
}
