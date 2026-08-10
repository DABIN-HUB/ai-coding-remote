package com.wangbin.ai.agent.daemon.event;

import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeltaEventAggregatorTest {

    @Test
    void preservesFullMessageAfterIntermediateDeltaFlush() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setEventAggregationMaxChars(5);
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties);

        List<AgentEvent> firstFlush = aggregator.accept(delta("hello"));
        assertThat(firstFlush).hasSize(1);
        assertThat(((AgentMessagePayload) firstFlush.getFirst().payload()).content()).isEqualTo("hello");

        assertThat(aggregator.accept(delta(" world"))).hasSize(1);
        List<AgentEvent> terminal = aggregator.accept(idle());

        assertThat(terminal).hasSize(2);
        assertThat(terminal.getFirst().type()).isEqualTo(AgentEventType.AGENT_MESSAGE);
        AgentMessagePayload finalPayload = (AgentMessagePayload) terminal.getFirst().payload();
        assertThat(finalPayload.content()).isEqualTo("hello world");
        assertThat(finalPayload.delta()).isFalse();
        assertThat(terminal.get(1).type()).isEqualTo(AgentEventType.SESSION_IDLE);
    }

    private AgentEvent delta(String content) {
        return new AgentEvent(null, "trace-1", "tenant-1", "user-1", "device-1", "project-1",
                "session-1", 1, AgentType.CODEX, AgentEventType.AGENT_MESSAGE_DELTA, null, null,
                new AgentMessagePayload("msg-1", "assistant", content, true, Map.of()), Map.of());
    }

    private AgentEvent idle() {
        return new AgentEvent(null, "trace-1", "tenant-1", "user-1", "device-1", "project-1",
                "session-1", 2, AgentType.CODEX, AgentEventType.SESSION_IDLE, null, null,
                new SessionPayload("native-1", AgentSessionStatus.IDLE, null, Map.of()), Map.of());
    }

}
