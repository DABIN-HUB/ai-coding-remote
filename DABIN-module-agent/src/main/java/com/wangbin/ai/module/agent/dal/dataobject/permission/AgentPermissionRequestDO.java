package com.wangbin.ai.module.agent.dal.dataobject.permission;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wangbin.ai.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("ai_code_permission_request")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentPermissionRequestDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String permissionId;
    private Long sessionId;
    private Long commandId;
    private Long deviceId;
    private Long projectId;
    private Long ownerUserId;
    private String permissionType;
    private String permissionStatus;
    private String title;
    private String reason;
    private String requestJson;
    private String decision;
    private String decisionReason;
    private Long decisionUserId;
    private Long decisionCommandId;
    private LocalDateTime requestedTime;
    private LocalDateTime decidedTime;
    private LocalDateTime resolvedTime;
    private String errorMessage;
}
