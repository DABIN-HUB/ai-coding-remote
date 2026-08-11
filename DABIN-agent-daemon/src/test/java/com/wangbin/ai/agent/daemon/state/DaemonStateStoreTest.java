package com.wangbin.ai.agent.daemon.state;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void installationIdIsStableAcrossStoreInstances() {
        withUserHome(tempDir, () -> {
            DaemonStateStore first = store();
            String installationId = first.getOrCreateInstallationId();
            DaemonStateStore second = store();

            assertThat(second.getOrCreateInstallationId()).isEqualTo(installationId);
        });
    }

    @Test
    void credentialStatePersistsWithoutLeakingSecretInToString() {
        withUserHome(tempDir, () -> {
            DaemonStateStore stateStore = store();
            DeviceCredentialState state = stateStore.newCredential(1L, "dev-1", "cred-1",
                    "super-secret", "http://control", "ws://relay");

            stateStore.saveCredential(state);

            DeviceCredentialState loaded = stateStore.loadCredential().orElseThrow();
            assertThat(loaded.getCredentialSecret()).isEqualTo("super-secret");
            assertThat(loaded.toString()).doesNotContain("super-secret");
            assertThat(Files.exists(tempDir.resolve(".agent-remote")
                    .resolve("credentials").resolve("device-credential.json"))).isTrue();
        });
    }

    private DaemonStateStore store() {
        return new DaemonStateStore(JsonMapper.builder().addModule(new JavaTimeModule()).build());
    }

    private void withUserHome(Path home, Runnable runnable) {
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            runnable.run();
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }
}
