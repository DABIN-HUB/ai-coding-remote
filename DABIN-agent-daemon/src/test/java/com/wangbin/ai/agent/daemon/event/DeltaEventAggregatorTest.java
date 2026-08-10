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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class DeltaEventAggregatorTest {

    @Test
    void preservesFullMessageAfterIntermediateDeltaFlush() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setEventAggregationMaxChars(5);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties, scheduler);
        AtomicLong sequence = new AtomicLong(10);

        try {
            List<AgentEvent> firstFlush = aggregator.accept(delta("hello", 1), sequence::incrementAndGet, ignored -> {
            });
            assertThat(firstFlush).hasSize(1);
            assertThat(((AgentMessagePayload) firstFlush.getFirst().payload()).content()).isEqualTo("hello");
            assertThat(firstFlush.getFirst().seq()).isEqualTo(11);

            assertThat(aggregator.accept(delta(" world", 2), sequence::incrementAndGet, ignored -> {
            })).hasSize(1);
            List<AgentEvent> terminal = aggregator.accept(idle(), sequence::incrementAndGet, ignored -> {
            });

            assertThat(terminal).hasSize(2);
            assertThat(terminal.getFirst().type()).isEqualTo(AgentEventType.AGENT_MESSAGE);
            AgentMessagePayload finalPayload = (AgentMessagePayload) terminal.getFirst().payload();
            assertThat(finalPayload.content()).isEqualTo("hello world");
            assertThat(finalPayload.delta()).isFalse();
            assertThat(terminal.getFirst().seq()).isLessThan(terminal.get(1).seq());
            assertThat(terminal.get(1).type()).isEqualTo(AgentEventType.SESSION_IDLE);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void flushesDeltaWhenAggregationWindowExpiresWithoutNextEvent() throws Exception {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setEventAggregationWindow(java.time.Duration.ofMillis(20));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties, scheduler);
        AtomicLong sequence = new AtomicLong(20);
        CountDownLatch latch = new CountDownLatch(1);
        List<AgentEvent> flushed = new java.util.concurrent.CopyOnWriteArrayList<>();

        try {
            assertThat(aggregator.accept(delta("hello", 1), sequence::incrementAndGet, event -> {
                flushed.add(event);
                latch.countDown();
            })).isEmpty();

            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(flushed).hasSize(1);
            assertThat(flushed.getFirst().type()).isEqualTo(AgentEventType.AGENT_MESSAGE_DELTA);
            assertThat(flushed.getFirst().seq()).isEqualTo(21);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void flushSessionEmitsRemainingDeltaAndFinalMessageWithFreshSequence() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties, scheduler);
        AtomicLong sequence = new AtomicLong(30);

        try {
            assertThat(aggregator.accept(delta("pending", 7), sequence::incrementAndGet, ignored -> {
            })).isEmpty();

            List<AgentEvent> flushed = aggregator.flushSession("session-1", sequence::incrementAndGet, ignored -> {
            });

            assertThat(flushed).hasSize(2);
            assertThat(flushed).extracting(AgentEvent::type)
                    .containsExactly(AgentEventType.AGENT_MESSAGE_DELTA, AgentEventType.AGENT_MESSAGE);
            assertThat(flushed).extracting(AgentEvent::seq)
                    .containsExactly(31L, 32L);
        } finally {
            scheduler.shutdownNow();
        }
    }

    private AgentEvent delta(String content, long seq) {
        return new AgentEvent(null, "trace-1", 1L, 11L, "device-1", "project-1",
                "session-1", seq, AgentType.CODEX, AgentEventType.AGENT_MESSAGE_DELTA, null, null,
                new AgentMessagePayload("msg-1", "assistant", content, true, Map.of()), Map.of());
    }

    private AgentEvent idle() {
        return new AgentEvent(null, "trace-1", 1L, 11L, "device-1", "project-1",
                "session-1", 99, AgentType.CODEX, AgentEventType.SESSION_IDLE, null, null,
                new SessionPayload("native-1", AgentSessionStatus.IDLE, null, Map.of()), Map.of());
    }

}
