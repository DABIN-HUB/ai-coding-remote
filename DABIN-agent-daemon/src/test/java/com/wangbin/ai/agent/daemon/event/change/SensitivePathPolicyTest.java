package com.wangbin.ai.agent.daemon.event.change;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitivePathPolicyTest {

    private final SensitivePathPolicy policy = new SensitivePathPolicy();

    @Test
    void detectsDefaultSensitivePaths() {
        assertThat(policy.isSensitive("src/Main.java")).isFalse();
        assertThat(policy.isSensitive(".env")).isTrue();
        assertThat(policy.isSensitive(".env.local")).isTrue();
        assertThat(policy.isSensitive("cert/private.pem")).isTrue();
        assertThat(policy.isSensitive("keys/server.key")).isTrue();
        assertThat(policy.isSensitive(".id_rsa")).isTrue();
        assertThat(policy.isSensitive("src/auth.json")).isTrue();
        assertThat(policy.isSensitive("config/credentials/db")).isTrue();
    }
}
