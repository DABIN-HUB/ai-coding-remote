package com.wangbin.ai.agent.daemon.state;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonStateStoreTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_CREDENTIAL_ID = "cred-1";
    private static final String TEST_CREDENTIAL_SECRET = "super-secret";
    private static final String TEST_CONTROL_PLANE_URL = "http://control";
    private static final String TEST_RELAY_URL = "ws://relay";
    private static final String DAEMON_BASE_DIR = ".agent-remote";
    private static final String CREDENTIALS_DIR = "credentials";
    private static final String DEVICE_CREDENTIAL_FILE = "device-credential.json";

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
            DeviceCredentialState state = stateStore.newCredential(TEST_TENANT_ID, TEST_DEVICE_ID,
                    TEST_CREDENTIAL_ID, TEST_CREDENTIAL_SECRET, TEST_CONTROL_PLANE_URL, TEST_RELAY_URL);

            stateStore.saveCredential(state);

            DeviceCredentialState loaded = stateStore.loadCredential().orElseThrow();
            assertThat(loaded.getCredentialSecret()).isEqualTo(TEST_CREDENTIAL_SECRET);
            assertThat(loaded.toString()).doesNotContain(TEST_CREDENTIAL_SECRET);
            assertThat(Files.exists(tempDir.resolve(DAEMON_BASE_DIR)
                    .resolve(CREDENTIALS_DIR).resolve(DEVICE_CREDENTIAL_FILE))).isTrue();
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
