package com.wangbin.ai.agent.contract.event;

import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventTypePriorityTest {

    @Test
    void shouldAssignCriticalPriorityToLosslessEvents() {
        assertThat(AgentEventType.PERMISSION_REQUIRED.defaultPriority()).isEqualTo(EventPriority.CRITICAL);
        assertThat(AgentEventType.PERMISSION_RESOLVED.defaultPriority()).isEqualTo(EventPriority.CRITICAL);
        assertThat(AgentEventType.TASK_COMPLETED.defaultPriority()).isEqualTo(EventPriority.CRITICAL);
        assertThat(AgentEventType.ERROR.defaultPriority()).isEqualTo(EventPriority.CRITICAL);
        assertThat(AgentEventType.SESSION_INTERRUPTED.defaultPriority()).isEqualTo(EventPriority.CRITICAL);
        assertThat(AgentEventType.SESSION_CONTROL_TIMEOUT.defaultPriority()).isEqualTo(EventPriority.CRITICAL);
        assertThat(AgentEventType.SESSION_COMPLETED.defaultPriority()).isEqualTo(EventPriority.CRITICAL);
        assertThat(AgentEventType.CHANGE_SET_FINALIZED.defaultPriority()).isEqualTo(EventPriority.CRITICAL);
    }

    @Test
    void shouldAllowTransientDeltaToBeDroppedOrMerged() {
        assertThat(AgentEventType.AGENT_MESSAGE_DELTA.defaultPriority()).isEqualTo(EventPriority.TRANSIENT);
        assertThat(AgentEventType.FILE_CHANGED.defaultPriority()).isEqualTo(EventPriority.NORMAL);
        assertThat(AgentEventType.DIFF_UPDATED.defaultPriority()).isEqualTo(EventPriority.NORMAL);
    }

}
