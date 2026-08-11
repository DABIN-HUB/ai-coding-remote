package com.wangbin.ai.agent.relay.backpressure;

import com.wangbin.ai.agent.contract.enums.EventPriority;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionOutboundChannelTest {

    private static final int SINGLE_MESSAGE_CAPACITY = 1;
    private static final String TEST_CONNECTION_ID = "conn-1";
    private static final String FIRST_PAYLOAD = "one";
    private static final String SECOND_PAYLOAD = "two";

    @Test
    void boundedChannelRejectsWhenCapacityIsFull() {
        ConnectionOutboundChannel channel = new ConnectionOutboundChannel(SINGLE_MESSAGE_CAPACITY);

        boolean first = channel.enqueue(new OutboundMessage(TEST_CONNECTION_ID, EventPriority.CRITICAL,
                FIRST_PAYLOAD, null));
        boolean second = channel.enqueue(new OutboundMessage(TEST_CONNECTION_ID, EventPriority.CRITICAL,
                SECOND_PAYLOAD, null));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }
}
