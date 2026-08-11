package com.wangbin.ai.module.agent.dal.dataobject.message;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wangbin.ai.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("ai_code_message")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentMessageDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String messageId;
    private Long sessionId;
    private Long commandId;
    private String role;
    private String messageType;
    private String content;
    private Long eventSeq;
    private String messageStatus;
    private String nativeItemId;
    private String createSource;
}
