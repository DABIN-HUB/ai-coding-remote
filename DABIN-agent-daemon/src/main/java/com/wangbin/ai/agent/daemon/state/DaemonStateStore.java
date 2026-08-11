package com.wangbin.ai.agent.daemon.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class DaemonStateStore {

    private final ObjectMapper objectMapper;
    private final Path baseDir;

    public DaemonStateStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.baseDir = Path.of(System.getProperty("user.home"), ".agent-remote");
    }

    public synchronized String getOrCreateInstallationId() {
        Path path = baseDir.resolve("state").resolve("device.json");
        try {
            if (Files.exists(path)) {
                return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                        DaemonInstallationState.class).installationId();
            }
            Files.createDirectories(path.getParent());
            DaemonInstallationState state = new DaemonInstallationState(UUID.randomUUID().toString());
            writeSecure(path, objectMapper.writeValueAsString(state));
            return state.installationId();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load daemon installation state", ex);
        }
    }

    public synchronized Optional<DeviceCredentialState> loadCredential() {
        Path path = credentialPath();
        try {
            return Files.exists(path)
                    ? Optional.of(objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                    DeviceCredentialState.class))
                    : Optional.empty();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load device credential state", ex);
        }
    }

    public synchronized void saveCredential(DeviceCredentialState state) {
        try {
            Path path = credentialPath();
            Files.createDirectories(path.getParent());
            writeSecure(path, objectMapper.writeValueAsString(state));
        } catch (IOException ex) {
            throw new IllegalStateException("failed to save device credential state", ex);
        }
    }

    public DeviceCredentialState newCredential(Long tenantId, String deviceId, String credentialId,
                                               String credentialSecret, String controlPlaneUrl, String relayUrl) {
        DeviceCredentialState state = new DeviceCredentialState();
        state.setTenantId(tenantId);
        state.setDeviceId(deviceId);
        state.setCredentialId(credentialId);
        state.setCredentialSecret(credentialSecret);
        state.setPairedAt(Instant.now());
        state.setControlPlaneUrl(controlPlaneUrl);
        state.setRelayUrl(relayUrl);
        return state;
    }

    private Path credentialPath() {
        return baseDir.resolve("credentials").resolve("device-credential.json");
    }

    private void writeSecure(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows uses the user's profile ACL. Do not log credential content.
        }
    }
}
