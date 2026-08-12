package com.wangbin.ai.agent.contract.session;

public record AgentCapabilities(
        boolean prompt,
        boolean resumeSession,
        boolean permission,
        boolean terminal,
        boolean fileDiff,
        boolean artifact,
        boolean plan,
        boolean imageInput,
        boolean cancel,
        boolean interrupt
) {

    public static AgentCapabilities codexDefault() {
        return new AgentCapabilities(true, false, true, false, true, true, false, false, false, false);
    }

    public static AgentCapabilities unknown() {
        return new AgentCapabilities(false, false, false, false, false, false, false, false, false, false);
    }

}
