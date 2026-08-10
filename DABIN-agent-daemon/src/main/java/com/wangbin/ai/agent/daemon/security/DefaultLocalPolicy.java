package com.wangbin.ai.agent.daemon.security;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class DefaultLocalPolicy implements LocalPolicy {

    @Override
    public boolean isWorkspaceAllowed(Path workspace) {
        return workspace != null && workspace.isAbsolute();
    }

    @Override
    public boolean isWriteAllowed(Path path) {
        return path != null && path.isAbsolute();
    }

}
