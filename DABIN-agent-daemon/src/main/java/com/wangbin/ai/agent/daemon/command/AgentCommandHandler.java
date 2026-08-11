package com.wangbin.ai.agent.daemon.command;

import com.wangbin.ai.agent.contract.command.AgentCommand;
import com.wangbin.ai.agent.daemon.cloud.relay.DaemonOutboundSender;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;

public interface AgentCommandHandler {

    void handle(AgentCommand command, DeviceCredentialState credential, DaemonOutboundSender outboundSender);
}
