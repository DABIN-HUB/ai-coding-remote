package com.wangbin.ai.agent.contract.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCapabilitiesTest {

    @Test
    void codexDefaultReportsImplementedPermissionCapabilities() {
        AgentCapabilities capabilities = AgentCapabilities.codexDefault();

        assertThat(capabilities.prompt()).isTrue();
        assertThat(capabilities.resumeSession()).isFalse();
        assertThat(capabilities.permission()).isTrue();
        assertThat(capabilities.terminal()).isFalse();
        assertThat(capabilities.fileDiff()).isTrue();
        assertThat(capabilities.plan()).isFalse();
        assertThat(capabilities.imageInput()).isFalse();
        assertThat(capabilities.cancel()).isFalse();
        assertThat(capabilities.interrupt()).isFalse();
    }

}
