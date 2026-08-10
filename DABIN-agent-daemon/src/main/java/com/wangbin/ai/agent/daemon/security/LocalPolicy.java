package com.wangbin.ai.agent.daemon.security;

import java.nio.file.Path;

public interface LocalPolicy {

    boolean isWorkspaceAllowed(Path workspace);

    boolean isWriteAllowed(Path path);

}
