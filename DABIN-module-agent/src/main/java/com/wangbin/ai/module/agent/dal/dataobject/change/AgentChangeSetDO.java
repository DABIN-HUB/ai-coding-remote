package com.wangbin.ai.module.agent.dal.dataobject.change;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wangbin.ai.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("ai_code_change_set")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentChangeSetDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String changeSetId;
    private Long sessionId;
    private Long commandId;
    private Long deviceId;
    private Long projectId;
    private Long ownerUserId;
    private String changeStatus;
    private Integer fileCount;
    private Integer additions;
    private Integer deletions;
    private String diffText;
    /**
     * SHA-256 of the platform-visible sanitized diff, not the raw local diff.
     */
    private String diffSha256;
    private Boolean diffTruncated;
    private Boolean filesTruncated;
    private LocalDateTime startedTime;
    private LocalDateTime completedTime;
    private String remark;
}
