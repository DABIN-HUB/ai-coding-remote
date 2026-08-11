package com.wangbin.ai.agent.daemon.cloud.controlplane;

public record PairDeviceResponse(
        Long tenantId,
        String deviceId,
        String credentialId,
        String credentialSecret
) {
}
