package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;

public interface ControlPlaneClient {

    PairDeviceResponse pair(String controlPlaneUrl, PairDeviceRequest request);

    RelayTicketResponse createDeviceRelayTicket(DeviceCredentialState credential);
}
