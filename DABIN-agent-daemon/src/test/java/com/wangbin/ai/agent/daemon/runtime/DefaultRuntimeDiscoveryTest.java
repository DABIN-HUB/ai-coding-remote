package com.wangbin.ai.agent.daemon.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRuntimeDiscoveryTest {

    @Test
    void selectCodexVersionShouldSkipCliWarnings() {
        String version = DefaultRuntimeDiscovery.selectCodexVersion(List.of(
                "WARNING: failed to clean up stale arg0 temp dirs: access denied",
                "codex-cli 0.147.0"));

        assertThat(version).isEqualTo("codex-cli 0.147.0");
    }

    @Test
    void selectCodexVersionShouldReturnNullWhenOutputHasNoVersionLine() {
        String version = DefaultRuntimeDiscovery.selectCodexVersion(List.of(
                "WARNING: failed to clean up stale arg0 temp dirs: access denied"));

        assertThat(version).isNull();
    }
}
