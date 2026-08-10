package com.wangbin.ai.agent.relay.backpressure;

import java.util.Optional;

public interface OutboundMessageQueue {

    boolean offer(OutboundMessage message);

    Optional<OutboundMessage> poll();

    int size();

    int capacity();

}
