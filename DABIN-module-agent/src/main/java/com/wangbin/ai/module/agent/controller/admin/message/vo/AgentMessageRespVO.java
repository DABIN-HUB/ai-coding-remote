package com.wangbin.ai.module.agent.controller.admin.message.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - Agent Message Response VO")
@Data
public class AgentMessageRespVO {

    @Schema(description = "编号")
    private Long id;
    @Schema(description = "消息业务编号")
    private String messageId;
    @Schema(description = "Session 表编号")
    private Long sessionId;
    @Schema(description = "Command 表编号")
    private Long commandId;
    @Schema(description = "角色，USER/ASSISTANT/SYSTEM/TOOL")
    private String role;
    @Schema(description = "消息类型，TEXT/ERROR/COMMAND_OUTPUT")
    private String messageType;
    @Schema(description = "最终消息内容")
    private String content;
    @Schema(description = "产生该最终消息的 AgentEvent seq")
    private Long eventSeq;
    @Schema(description = "消息状态，本阶段只持久化 FINAL")
    private String messageStatus;
    @Schema(description = "白名单 Native Item ID")
    private String nativeItemId;
    @Schema(description = "创建来源，USER_COMMAND/AGENT_EVENT")
    private String createSource;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
