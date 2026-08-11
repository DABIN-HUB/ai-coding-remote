package com.wangbin.ai.module.agent.service.device;

import com.wangbin.ai.agent.contract.coordination.DevicePresencePayload;

public interface DevicePresenceService {

    DevicePresencePayload getPresence(String deviceId);
}
