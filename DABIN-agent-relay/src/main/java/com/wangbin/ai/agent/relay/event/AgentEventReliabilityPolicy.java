package com.wangbin.ai.agent.relay.event;

import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import org.springframework.stereotype.Component;

@Component
public class AgentEventReliabilityPolicy {

    public boolean shouldPublishDurably(AgentEvent event) {
        if (event == null || event.type() == AgentEventType.AGENT_MESSAGE_DELTA
                || event.type() == AgentEventType.FILE_CHANGED
                || event.type() == AgentEventType.DIFF_UPDATED) {
            return false;
        }
        return event.priority() == EventPriority.CRITICAL || event.priority() == EventPriority.IMPORTANT;
    }
}
