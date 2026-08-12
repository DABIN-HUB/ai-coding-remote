package com.wangbin.ai.module.agent.controller.admin.change.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - Agent FileChange Response VO")
@Data
public class AgentFileChangeRespVO {

    @Schema(description = "编号")
    private Long id;
    @Schema(description = "文件变更业务编号")
    private String fileChangeId;
    @Schema(description = "Workspace 相对路径")
    private String relativePath;
    @Schema(description = "重命名前 Workspace 相对路径")
    private String oldRelativePath;
    @Schema(description = "变更类型")
    private String changeType;
    @Schema(description = "新增行数")
    private Integer additions;
    @Schema(description = "删除行数")
    private Integer deletions;
    @Schema(description = "是否二进制文件")
    private Boolean binary;
    @Schema(description = "Patch 是否被截断")
    private Boolean patchTruncated;
    @Schema(description = "敏感文件 Patch 是否被脱敏")
    private Boolean redacted;
    @Schema(description = "摘要")
    private String summary;
    @Schema(description = "单文件 Unified Diff Patch，仅详情接口返回")
    private String patchText;
    @Schema(description = "平台可见 Patch SHA-256")
    private String patchSha256;
}
