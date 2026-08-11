package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PairDeviceResponse(
        Long tenantId,
        String deviceId,
        String credentialId,
        String credentialSecret
) {
}
