package com.wangbin.ai.agent.daemon.adapter.codex;

import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;

public class CodexPendingPermissionCapacityException extends AgentCapabilityException {

    public CodexPendingPermissionCapacityException(String message) {
        super(message);
    }
}
