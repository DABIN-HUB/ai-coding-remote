package com.wangbin.ai.agent.daemon.workspace;

import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.security.LocalPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DefaultWorkspaceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void validatesExistingWorkspaceUsingRealPath() throws Exception {
        Path allowedRoot = Files.createDirectory(tempDir.resolve("allowed")).toRealPath();
        DefaultWorkspaceManager manager = new DefaultWorkspaceManager(allowOnly(allowedRoot));

        Path workspace = manager.validateWorkspace(allowedRoot.toString());

        assertThat(workspace).isEqualTo(allowedRoot);
    }

    @Test
    void rejectsPathTraversalOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace")).toRealPath();
        Path outside = Files.createDirectory(tempDir.resolve("outside")).toRealPath();
        Files.writeString(outside.resolve("secret.txt"), "secret");
        DefaultWorkspaceManager manager = new DefaultWorkspaceManager(allowOnly(workspace));

        assertThatThrownBy(() -> manager.resolveWithinWorkspace(workspace, "../outside/secret.txt"))
                .isInstanceOf(AgentCapabilityException.class)
                .hasMessageContaining("path escapes workspace");
    }

    @Test
    void rejectsSymlinkEscapingWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace")).toRealPath();
        Path outside = Files.createDirectory(tempDir.resolve("outside")).toRealPath();
        Path target = Files.writeString(outside.resolve("secret.txt"), "secret").toRealPath();
        Path link = workspace.resolve("linked-secret.txt");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            assumeTrue(false, "current filesystem does not allow creating symlinks for this test: " + ex.getMessage());
        }
        DefaultWorkspaceManager manager = new DefaultWorkspaceManager(allowOnly(workspace));

        assertThatThrownBy(() -> manager.resolveWithinWorkspace(workspace, "linked-secret.txt"))
                .isInstanceOf(AgentCapabilityException.class)
                .hasMessageContaining("path escapes workspace");
    }

    private LocalPolicy allowOnly(Path allowedRoot) {
        return new LocalPolicy() {

            @Override
            public boolean isWorkspaceAllowed(Path workspace) {
                return workspace != null && workspace.startsWith(allowedRoot);
            }

            @Override
            public boolean isWriteAllowed(Path path) {
                return path != null && path.startsWith(allowedRoot);
            }

        };
    }

}
