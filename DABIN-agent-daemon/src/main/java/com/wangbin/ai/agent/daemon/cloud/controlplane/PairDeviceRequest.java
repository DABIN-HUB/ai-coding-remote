package com.wangbin.ai.agent.daemon.cloud.controlplane;

public record PairDeviceRequest(
        String pairingCode,
        String installationId,
        String deviceName,
        String hostname,
        String osName,
        String osVersion,
        String osArch,
        String daemonVersion
) {
}
