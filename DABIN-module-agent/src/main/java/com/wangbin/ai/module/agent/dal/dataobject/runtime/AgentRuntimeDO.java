package com.wangbin.ai.module.agent.dal.dataobject.runtime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wangbin.ai.framework.tenant.core.db.TenantBaseDO;
import com.wangbin.ai.module.agent.enums.RuntimeStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("ai_code_runtime")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentRuntimeDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long deviceId;
    private String runtimeId;
    private String runtimeType;
    private String runtimeVersion;
    private String executablePath;
    private String runtimeStatus;
    private String capabilitiesJson;
    private LocalDateTime lastDiscoveredTime;
    private String remark;

    public boolean isAvailable() {
        return RuntimeStatus.AVAILABLE.name().equals(runtimeStatus);
    }
}
