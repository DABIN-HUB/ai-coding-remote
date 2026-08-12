package com.wangbin.ai.module.agent.controller.admin.change.vo;

import com.wangbin.ai.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - Agent ChangeSet 分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentChangeSetPageReqVO extends PageParam {

    @Schema(description = "平台 Session 业务编号")
    private String sessionId;

    @Schema(description = "平台 Project 业务编号")
    private String projectId;

    @Schema(description = "平台 Command 业务编号")
    private String commandId;

    @Schema(description = "变更集状态")
    private String changeStatus;
}
