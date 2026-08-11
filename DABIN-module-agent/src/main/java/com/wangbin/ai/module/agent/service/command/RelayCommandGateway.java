package com.wangbin.ai.module.agent.service.command;

import com.wangbin.ai.agent.contract.coordination.RelayCommandDispatchPayload;

public interface RelayCommandGateway {

    void dispatch(RelayCommandDispatchPayload payload);
}
