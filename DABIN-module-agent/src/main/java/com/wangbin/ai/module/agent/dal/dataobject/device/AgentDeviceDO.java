package com.wangbin.ai.module.agent.dal.dataobject.device;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wangbin.ai.framework.tenant.core.db.TenantBaseDO;
import com.wangbin.ai.module.agent.enums.DeviceStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("ai_code_device")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentDeviceDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String deviceId;
    private String installationId;
    private Long ownerUserId;
    private String deviceName;
    private String hostname;
    private String osName;
    private String osVersion;
    private String osArch;
    private String daemonVersion;
    /**
     * Registration status, not realtime online state. Realtime presence is stored in Redis.
     */
    private String deviceStatus;
    private String remark;

    public boolean isActive() {
        return DeviceStatus.ACTIVE.name().equals(deviceStatus);
    }
}
