package com.wangbin.ai.module.agent.controller.admin.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Schema(description = "Daemon - Device Relay Ticket Response VO")
@Data
@AllArgsConstructor
public class AgentDeviceRelayTicketRespVO {

    @Schema(description = "一次性短期 Relay Ticket", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ticket;

    @Schema(description = "过期时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant expireAt;
}
