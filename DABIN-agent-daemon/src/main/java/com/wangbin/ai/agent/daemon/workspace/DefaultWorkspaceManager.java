package com.wangbin.ai.agent.daemon.workspace;

import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.security.LocalPolicy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DefaultWorkspaceManager implements WorkspaceManager {

    private final LocalPolicy localPolicy;

    public DefaultWorkspaceManager(LocalPolicy localPolicy) {
        this.localPolicy = localPolicy;
    }

    @Override
    public Path validateWorkspace(String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            throw new AgentCapabilityException("workspace path must not be blank");
        }
        Path normalized = Path.of(workspacePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new AgentCapabilityException("workspace does not exist or is not a directory: " + normalized);
        }
        Path realWorkspace = toRealPath(normalized, "workspace");
        if (!localPolicy.isWorkspaceAllowed(realWorkspace)) {
            throw new AgentCapabilityException("workspace is not allowed by local policy: " + realWorkspace);
        }
        return realWorkspace;
    }

    @Override
    public Path resolveWithinWorkspace(Path workspace, String relativePath) {
        Path realWorkspace = toRealPath(workspace.toAbsolutePath().normalize(), "workspace");
        Path resolved = realWorkspace.resolve(relativePath).toAbsolutePath().normalize();
        Path boundary = Files.exists(resolved) ? toRealPath(resolved, "workspace path") : realExistingParent(resolved);
        if (!boundary.startsWith(realWorkspace)) {
            throw new AgentCapabilityException("path escapes workspace: " + relativePath);
        }
        return resolved;
    }

    private Path realExistingParent(Path path) {
        Path parent = path.getParent();
        if (parent == null || !Files.exists(parent)) {
            throw new AgentCapabilityException("workspace path parent does not exist: " + path);
        }
        return toRealPath(parent, "workspace path parent");
    }

    private Path toRealPath(Path path, String label) {
        try {
            return path.toRealPath();
        } catch (IOException ex) {
            throw new AgentCapabilityException("failed to resolve real " + label + ": " + path, ex);
        }
    }

}
