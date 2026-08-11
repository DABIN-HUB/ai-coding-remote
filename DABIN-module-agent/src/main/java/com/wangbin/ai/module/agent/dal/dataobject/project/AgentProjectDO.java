package com.wangbin.ai.module.agent.dal.dataobject.project;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wangbin.ai.framework.tenant.core.db.TenantBaseDO;
import com.wangbin.ai.module.agent.enums.ProjectStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("ai_code_project")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentProjectDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long deviceId;
    private String projectId;
    private String localProjectId;
    private Long ownerUserId;
    private String projectName;
    private String workspacePath;
    private String workspaceRealPath;
    private String agentType;
    private String projectStatus;
    private LocalDateTime lastSeenTime;
    private String remark;

    public boolean isActive() {
        return ProjectStatus.ACTIVE.name().equals(projectStatus);
    }
}
