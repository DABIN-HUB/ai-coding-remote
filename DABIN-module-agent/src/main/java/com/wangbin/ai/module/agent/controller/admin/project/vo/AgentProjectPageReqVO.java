package com.wangbin.ai.module.agent.controller.admin.project.vo;

import com.wangbin.ai.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - Agent Project 分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentProjectPageReqVO extends PageParam {

    @Schema(description = "设备表编号")
    private Long deviceDbId;

    @Schema(description = "项目名称")
    private String projectName;

    @Schema(description = "项目状态，ACTIVE/DISABLED")
    private String projectStatus;
}
