package com.wangbin.ai.agent.contract.event;

import com.wangbin.ai.agent.contract.enums.SessionControlAction;
import com.wangbin.ai.agent.contract.enums.SessionInterruptInitiator;

import java.util.Map;

/**
 * Durable lifecycle event emitted after the native turn confirms it was interrupted.
 */
public record SessionInterruptedPayload(
        String nativeSessionId,
        String targetCommandId,
        String controlCommandId,
        SessionControlAction action,
        SessionInterruptInitiator initiatedBy,
        String reason,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public SessionInterruptedPayload {
        action = action == null ? SessionControlAction.INTERRUPT : action;
        initiatedBy = initiatedBy == null ? SessionInterruptInitiator.SYSTEM : initiatedBy;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
