package com.wangbin.ai.agent.daemon.cloud.relay;

public enum RelayConnectionState {

    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    RECONNECT_WAIT,
    STOPPED
}
