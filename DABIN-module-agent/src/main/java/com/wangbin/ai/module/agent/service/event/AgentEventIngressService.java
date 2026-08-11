package com.wangbin.ai.module.agent.service.event;

import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.coordination.AgentEventIngressPayload;

public interface AgentEventIngressService {

    void handleAgentEvent(AgentEventIngressPayload payload);

    void handleCommandAck(CommandAck ack);
}
