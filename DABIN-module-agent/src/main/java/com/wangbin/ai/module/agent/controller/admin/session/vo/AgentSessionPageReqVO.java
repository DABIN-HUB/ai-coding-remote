package com.wangbin.ai.module.agent.controller.admin.session.vo;

import com.wangbin.ai.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - Agent Session 分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentSessionPageReqVO extends PageParam {

    @Schema(description = "项目表编号")
    private Long projectDbId;

    @Schema(description = "Session 状态")
    private String sessionStatus;
}
