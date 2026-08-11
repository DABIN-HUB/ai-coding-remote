package com.wangbin.ai.module.agent.dal.dataobject.device;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wangbin.ai.framework.tenant.core.db.TenantBaseDO;
import com.wangbin.ai.module.agent.enums.CredentialStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("ai_code_device_credential")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentDeviceCredentialDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long deviceId;
    private String credentialId;
    private String secretHash;
    private String credentialStatus;
    private LocalDateTime expireTime;
    private LocalDateTime lastUsedTime;
    private LocalDateTime revokedTime;
    private String remark;

    public boolean isActive() {
        return CredentialStatus.ACTIVE.name().equals(credentialStatus);
    }
}
