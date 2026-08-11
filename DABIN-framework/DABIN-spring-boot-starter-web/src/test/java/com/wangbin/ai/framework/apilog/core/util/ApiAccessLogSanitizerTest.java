package com.wangbin.ai.framework.apilog.core.util;

import com.wangbin.ai.framework.common.pojo.CommonResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAccessLogSanitizerTest {

    @Test
    void sanitizeJsonShouldRemoveAgentSecrets() {
        String sanitized = ApiAccessLogSanitizer.sanitizeJson("""
                {
                  "pairingCode": "ABCD-2345",
                  "credentialSecret": "secret-value",
                  "deviceName": "local-device"
                }
                """, null);

        assertThat(sanitized).doesNotContain("ABCD-2345", "secret-value", "pairingCode", "credentialSecret");
        assertThat(sanitized).contains("local-device");
    }

    @Test
    void sanitizeMapShouldRemoveDefaultAndCustomSecretKeys() {
        String sanitized = ApiAccessLogSanitizer.sanitizeMap(
                Map.of("token", "token-value", "relayTicket", "ticket-value", "safe", "visible"),
                new String[]{"safe"});

        assertThat(sanitized).doesNotContain("token-value", "ticket-value", "visible");
        assertThat(sanitized).isEqualTo("{}");
    }

    @Test
    void sanitizeCommonResultShouldRemoveNestedLoginTokens() {
        String sanitized = ApiAccessLogSanitizer.sanitizeJson(CommonResult.success(Map.of(
                "accessToken", "access-token-value",
                "refreshToken", "refresh-token-value",
                "userId", 1L
        )), null);

        assertThat(sanitized).doesNotContain("access-token-value", "refresh-token-value",
                "accessToken", "refreshToken");
        assertThat(sanitized).contains("\"userId\":1");
    }
}
