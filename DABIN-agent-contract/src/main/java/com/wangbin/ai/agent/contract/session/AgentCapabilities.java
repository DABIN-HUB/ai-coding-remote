package com.wangbin.ai.agent.contract.session;

public record AgentCapabilities(
        boolean prompt,
        boolean resumeSession,
        boolean permission,
        boolean terminal,
        boolean fileDiff,
        boolean plan,
        boolean imageInput,
        boolean cancel,
        boolean interrupt
) {

    public static AgentCapabilities codexDefault() {
        return new AgentCapabilities(true, true, true, true, true, true, true, true, true);
    }

    public static AgentCapabilities unknown() {
        return new AgentCapabilities(false, false, false, false, false, false, false, false, false);
    }

}
