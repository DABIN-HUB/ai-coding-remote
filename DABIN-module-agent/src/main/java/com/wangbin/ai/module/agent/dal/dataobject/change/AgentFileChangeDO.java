package com.wangbin.ai.module.agent.dal.dataobject.change;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wangbin.ai.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("ai_code_file_change")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentFileChangeDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String fileChangeId;
    private Long changeSetId;
    private Long sessionId;
    private Long commandId;
    private String relativePath;
    private String oldRelativePath;
    private String changeType;
    private Integer additions;
    private Integer deletions;
    private Boolean binaryFile;
    private String patchText;
    /**
     * SHA-256 of the platform-visible sanitized file patch.
     */
    private String patchSha256;
    private Boolean patchTruncated;
    private Boolean redacted;
    private String summary;
}
