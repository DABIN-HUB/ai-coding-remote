package com.wangbin.ai.agent.daemon.event.change;

import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Converts Codex native file paths into workspace-relative platform paths.
 * Absolute paths are accepted only when they resolve under the local workspace.
 */
@Component
public class WorkspaceRelativePathNormalizer {

    private static final char NUL = '\0';

    private final WorkspaceManager workspaceManager;

    public WorkspaceRelativePathNormalizer(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    public String normalize(Path workspace, String path) {
        if (workspace == null || path == null || path.isBlank() || path.indexOf(NUL) >= 0) {
            throw new AgentCapabilityException("workspace-relative path is invalid");
        }
        String cleaned = stripDiffPrefix(path.trim().replace('\\', '/'));
        Path candidate = Path.of(cleaned);
        Path resolved;
        if (candidate.isAbsolute()) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (!absolute.startsWith(workspace)) {
                throw new AgentCapabilityException("path escapes workspace");
            }
            String relativeCandidate = workspace.relativize(absolute).toString();
            resolved = workspaceManager.resolveWithinWorkspace(workspace, relativeCandidate);
        } else {
            if (cleaned.startsWith("/") || cleaned.startsWith("../") || cleaned.equals("..") || cleaned.contains("/../")) {
                throw new AgentCapabilityException("path escapes workspace");
            }
            resolved = workspaceManager.resolveWithinWorkspace(workspace, cleaned);
        }
        String relative = workspace.relativize(resolved.toAbsolutePath().normalize()).toString().replace('\\', '/');
        if (relative.isBlank() || relative.startsWith("../") || relative.equals("..") || relative.contains("/../")) {
            throw new AgentCapabilityException("path escapes workspace");
        }
        return relative;
    }

    private String stripDiffPrefix(String path) {
        if (path.startsWith("a/") || path.startsWith("b/")) {
            return path.substring(2);
        }
        return path;
    }
}
