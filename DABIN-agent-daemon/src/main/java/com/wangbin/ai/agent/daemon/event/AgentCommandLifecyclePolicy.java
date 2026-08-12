package com.wangbin.ai.agent.daemon.event;

import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.event.AgentErrorPayload;
import com.wangbin.ai.agent.contract.event.AgentEvent;

public final class AgentCommandLifecyclePolicy {

    private AgentCommandLifecyclePolicy() {
    }

    public static boolean isTerminalForActiveCommand(AgentEvent event) {
        if (event == null) {
            return false;
        }
        if (event.type() == AgentEventType.ERROR && event.payload() instanceof AgentErrorPayload payload) {
            return !payload.retryable();
        }
        return event.type() == AgentEventType.SESSION_IDLE
                || event.type() == AgentEventType.SESSION_INTERRUPTED
                || event.type() == AgentEventType.SESSION_COMPLETED;
    }
}
