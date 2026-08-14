package com.wangbin.ai.agent.daemon.cloud.controlplane;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpControlPlaneClientTest {

    @Test
    void apiUrlShouldAppendAdminApiPrefix() {
        assertThat(HttpControlPlaneClient.apiUrl("http://127.0.0.1:48080", "/agent/device/pair"))
                .isEqualTo("http://127.0.0.1:48080/admin-api/agent/device/pair");
    }

    @Test
    void apiUrlShouldNotDuplicateAdminApiPrefix() {
        assertThat(HttpControlPlaneClient.apiUrl("http://127.0.0.1:48080/admin-api/", "/agent/device/pair"))
                .isEqualTo("http://127.0.0.1:48080/admin-api/agent/device/pair");
    }
}
