package com.wangbin.ai.agent.daemon.workspace;

import java.nio.file.Path;

public interface WorkspaceManager {

    Path validateWorkspace(String workspacePath);

    Path resolveWithinWorkspace(Path workspace, String relativePath);

}
