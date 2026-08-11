package com.wangbin.ai.agent.relay.backpressure;

import com.wangbin.ai.agent.contract.enums.EventPriority;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionOutboundChannelTest {

    @Test
    void boundedChannelRejectsWhenCapacityIsFull() {
        ConnectionOutboundChannel channel = new ConnectionOutboundChannel(1);

        boolean first = channel.enqueue(new OutboundMessage("conn-1", EventPriority.CRITICAL, "one", null));
        boolean second = channel.enqueue(new OutboundMessage("conn-1", EventPriority.CRITICAL, "two", null));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }
}
