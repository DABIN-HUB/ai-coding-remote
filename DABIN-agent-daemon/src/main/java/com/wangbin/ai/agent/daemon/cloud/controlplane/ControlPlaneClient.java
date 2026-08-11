package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;

public interface ControlPlaneClient {

    PairDeviceResponse pair(String controlPlaneUrl, PairDeviceRequest request);

    RelayTicketResponse createDeviceRelayTicket(DeviceCredentialState credential);

    default RegisterProjectResponse registerProject(DeviceCredentialState credential, RegisterProjectRequest request) {
        throw new UnsupportedOperationException("project register is not implemented");
    }

    default void reportRuntime(DeviceCredentialState credential, RuntimeReportRequest request) {
        throw new UnsupportedOperationException("runtime report is not implemented");
    }
}
