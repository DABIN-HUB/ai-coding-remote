package com.wangbin.ai.module.agent.controller.admin.artifact.vo;

import com.wangbin.ai.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - Agent Artifact 分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentArtifactPageReqVO extends PageParam {

    @Schema(description = "平台 Session 业务编号")
    private String sessionId;

    @Schema(description = "平台 Project 业务编号")
    private String projectId;

    @Schema(description = "Artifact 状态")
    private String artifactStatus;

    @Schema(description = "Artifact 来源类型")
    private String sourceType;
}
