package com.wangbin.ai.module.agent.service.event;

import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.coordination.AgentEventIngressPayload;
import com.wangbin.ai.agent.contract.coordination.CommandAckIngressPayload;

public interface AgentEventIngressService {

    void handleAgentEvent(AgentEventIngressPayload payload);

    void handleCommandAck(CommandAckIngressPayload payload);
}
