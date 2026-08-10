package com.wangbin.ai.agent.relay.backpressure;

import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.relay.config.AgentRelayProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedOutboundMessageQueueTest {

    @Test
    void shouldRejectMessagesWhenCapacityIsReached() {
        AgentRelayProperties properties = new AgentRelayProperties();
        properties.setOutboundQueueCapacity(1);
        BoundedOutboundMessageQueue queue = new BoundedOutboundMessageQueue(properties);

        assertThat(queue.offer(new OutboundMessage("c1", EventPriority.NORMAL, "first", null))).isTrue();
        assertThat(queue.offer(new OutboundMessage("c1", EventPriority.TRANSIENT, "second", null))).isFalse();
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.capacity()).isEqualTo(1);
    }

    @Test
    void shouldRejectCriticalMessagesExplicitlyWhenCapacityIsReached() {
        BoundedOutboundMessageQueue queue = new BoundedOutboundMessageQueue(1);

        assertThat(queue.offer(new OutboundMessage("c1", EventPriority.CRITICAL, "first", null))).isTrue();
        assertThat(queue.offer(new OutboundMessage("c1", EventPriority.CRITICAL, "critical", null))).isFalse();
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    void shouldMakeRoomForHigherPriorityMessagesByDroppingLowerPriorityMessages() {
        BoundedOutboundMessageQueue queue = new BoundedOutboundMessageQueue(2);

        assertThat(queue.offer(new OutboundMessage("c1", EventPriority.TRANSIENT, "transient", null))).isTrue();
        assertThat(queue.offer(new OutboundMessage("c1", EventPriority.NORMAL, "normal", null))).isTrue();
        assertThat(queue.offer(new OutboundMessage("c1", EventPriority.IMPORTANT, "important", null))).isTrue();

        assertThat(queue.drainAll()).extracting(OutboundMessage::payload)
                .containsExactly("normal", "important");
    }

}
