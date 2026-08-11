package com.wangbin.ai.module.agent.controller.admin.session.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - Agent Command Response VO")
@Data
public class AgentCommandRespVO {

    @Schema(description = "编号")
    private Long id;
    @Schema(description = "命令业务编号")
    private String commandId;
    @Schema(description = "Session 表编号")
    private Long sessionId;
    @Schema(description = "命令类型")
    private String commandType;
    @Schema(description = "命令状态")
    private String commandStatus;
    @Schema(description = "客户端幂等请求编号")
    private String requestId;
    @Schema(description = "ACK 代码")
    private String ackCode;
    @Schema(description = "ACK 消息")
    private String ackMessage;
    @Schema(description = "ACK 时间")
    private LocalDateTime ackedTime;
}
