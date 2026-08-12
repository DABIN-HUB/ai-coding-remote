package com.wangbin.ai.module.agent.controller.admin.change.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - Agent ChangeSet Diff Response VO")
@Data
public class AgentChangeSetDiffRespVO {

    @Schema(description = "变更集业务编号")
    private String changeSetId;

    @Schema(description = "Unified Diff 快照，可能被截断")
    private String diffText;

    @Schema(description = "平台可见 Diff SHA-256")
    private String diffSha256;

    @Schema(description = "Diff 是否被截断")
    private Boolean diffTruncated;
}
