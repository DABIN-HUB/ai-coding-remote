package com.wangbin.ai.module.agent.controller.admin.change.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - Agent ChangeSet 详情 Response VO")
@Data
public class AgentChangeSetDetailRespVO {

    @Schema(description = "变更集基础信息")
    private AgentChangeSetRespVO changeSet;

    @Schema(description = "变更文件列表，不包含大 Patch")
    private List<AgentFileChangeRespVO> files;
}
