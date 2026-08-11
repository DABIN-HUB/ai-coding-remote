package com.wangbin.ai.agent.daemon.cloud.relay;

import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.event.AgentEvent;

public interface DaemonOutboundSender {

    boolean sendCommandAck(CommandAck ack);

    boolean sendAgentEvent(AgentEvent event);
}
