package com.wangbin.ai.module.agent.dal.dataobject.session;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wangbin.ai.framework.tenant.core.db.TenantBaseDO;
import com.wangbin.ai.module.agent.enums.AgentSessionDbStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("ai_code_session")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentSessionDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String sessionId;
    private Long deviceId;
    private Long projectId;
    private Long runtimeId;
    private Long ownerUserId;
    private String agentType;
    private String nativeSessionId;
    private String sessionStatus;
    private Long lastEventSeq;
    private LocalDateTime lastActiveTime;
    private LocalDateTime startedTime;
    private LocalDateTime closedTime;
    private String errorMessage;

    public boolean isClosed() {
        return AgentSessionDbStatus.CLOSED.name().equals(sessionStatus)
                || AgentSessionDbStatus.FAILED.name().equals(sessionStatus);
    }
}
