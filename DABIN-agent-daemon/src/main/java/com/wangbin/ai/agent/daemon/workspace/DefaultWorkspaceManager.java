package com.wangbin.ai.agent.daemon.workspace;

import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.security.LocalPolicy;
import org.springframework.stereotype.Component;

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
        Path canonical = Path.of(workspacePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(canonical)) {
            throw new AgentCapabilityException("workspace does not exist or is not a directory: " + canonical);
        }
        if (!localPolicy.isWorkspaceAllowed(canonical)) {
            throw new AgentCapabilityException("workspace is not allowed by local policy: " + canonical);
        }
        return canonical;
    }

    @Override
    public Path resolveWithinWorkspace(Path workspace, String relativePath) {
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        Path resolved = normalizedWorkspace.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(normalizedWorkspace)) {
            throw new AgentCapabilityException("path escapes workspace: " + relativePath);
        }
        return resolved;
    }

}
