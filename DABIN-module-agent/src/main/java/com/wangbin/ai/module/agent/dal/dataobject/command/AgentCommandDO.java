package com.wangbin.ai.module.agent.dal.dataobject.command;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wangbin.ai.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("ai_code_command")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentCommandDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String commandId;
    private Long sessionId;
    private Long deviceId;
    private Long projectId;
    private Long ownerUserId;
    private String commandType;
    private String commandStatus;
    private String requestId;
    private String payloadJson;
    private String ackCode;
    private String ackMessage;
    private LocalDateTime createdDispatchTime;
    private LocalDateTime deliveredTime;
    private LocalDateTime ackedTime;
    private LocalDateTime completedTime;
    private String errorMessage;
}
