package com.wangbin.ai.agent.relay.dispatch;

import com.wangbin.ai.agent.contract.event.AgentEvent;
import reactor.core.publisher.Mono;

public interface EventDispatcher {

    Mono<Void> dispatchToDevice(String deviceId, AgentEvent event);

    Mono<Void> dispatchToUser(Long tenantId, Long userId, AgentEvent event);

}
