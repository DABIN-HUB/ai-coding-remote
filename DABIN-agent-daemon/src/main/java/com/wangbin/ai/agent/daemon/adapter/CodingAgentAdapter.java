package com.wangbin.ai.agent.daemon.adapter;

import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.session.AgentCapabilities;
import com.wangbin.ai.agent.contract.session.AgentSession;
import com.wangbin.ai.agent.contract.session.PromptCommand;
import com.wangbin.ai.agent.contract.session.SessionStartRequest;
import reactor.core.publisher.Flux;

public interface CodingAgentAdapter {

    AgentType agentType();

    AgentCapabilities capabilities();

    AgentSession startSession(SessionStartRequest request);

    void sendPrompt(String sessionId, PromptCommand command);

    void interrupt(String sessionId);

    default void cancelPendingPermissions(String sessionId) {
    }

    void resolvePermission(String sessionId, String permissionId, PermissionDecision decision, String decisionCommandId);

    Flux<AgentEvent> events(String sessionId);

    void closeSession(String sessionId);

    default void closeSession(String sessionId, String controlCommandId) {
        closeSession(sessionId);
    }

}
