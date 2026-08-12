package com.wangbin.ai.module.agent.controller.admin.session.vo;

import com.wangbin.ai.agent.contract.enums.SessionControlAction;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - Agent Session Control Response VO")
@Data
public class AgentSessionControlRespVO {

    @Schema(description = "控制命令业务编号")
    private String controlCommandId;

    @Schema(description = "平台 Session 业务编号")
    private String sessionId;

    @Schema(description = "目标 Prompt Command 业务编号")
    private String targetCommandId;

    @Schema(description = "Session 控制动作")
    private SessionControlAction action;

    @Schema(description = "控制命令状态")
    private String commandStatus;

    @Schema(description = "当前 Session 状态")
    private String sessionStatus;
}
