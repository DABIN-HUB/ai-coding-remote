package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ControlPlaneResult<T>(
        Integer code,
        T data,
        String msg
) {
}
