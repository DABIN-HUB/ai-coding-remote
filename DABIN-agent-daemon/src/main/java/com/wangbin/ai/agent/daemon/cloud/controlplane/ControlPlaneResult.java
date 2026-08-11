package com.wangbin.ai.agent.daemon.cloud.controlplane;

public record ControlPlaneResult<T>(
        Integer code,
        T data,
        String msg
) {
}
