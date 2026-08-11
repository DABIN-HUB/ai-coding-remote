package com.wangbin.ai.module.agent.service.device;

import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;

public record DeviceCredentialIdentity(
        Long tenantId,
        Long ownerUserId,
        AgentDeviceDO device
) {
}
