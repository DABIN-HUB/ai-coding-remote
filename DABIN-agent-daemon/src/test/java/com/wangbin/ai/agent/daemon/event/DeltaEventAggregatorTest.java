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
    void keepsIntermediateFlushesAsDeltaAndFinalizesOnlyOnCompletedMessage() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setEventAggregationMaxChars(5);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties, scheduler);
        AtomicLong sequence = new AtomicLong(10);

        try {
            List<AgentEvent> firstFlush = aggregator.accept(delta("hello", 1), sequence::incrementAndGet, ignored -> {
            });
            assertThat(firstFlush).hasSize(1);
            assertThat(firstFlush.getFirst().type()).isEqualTo(AgentEventType.AGENT_MESSAGE_DELTA);
            assertThat(((AgentMessagePayload) firstFlush.getFirst().payload()).content()).isEqualTo("hello");
            assertThat(firstFlush.getFirst().seq()).isEqualTo(11);

            assertThat(aggregator.accept(delta(" world", 2), sequence::incrementAndGet, ignored -> {
            })).hasSize(1);
            List<AgentEvent> finalized = aggregator.accept(finalMessage("hello world", 3), sequence::incrementAndGet,
                    ignored -> {
                    });

            assertThat(finalized).hasSize(1);
            assertThat(finalized.getFirst().type()).isEqualTo(AgentEventType.AGENT_MESSAGE);
            AgentMessagePayload finalPayload = (AgentMessagePayload) finalized.getFirst().payload();
            assertThat(finalPayload.content()).isEqualTo("hello world");
            assertThat(finalPayload.delta()).isFalse();
            assertThat(finalized.getFirst().seq()).isGreaterThan(firstFlush.getFirst().seq());

            List<AgentEvent> terminal = aggregator.accept(idle(), sequence::incrementAndGet, ignored -> {
            });

            assertThat(terminal).hasSize(1);
            assertThat(terminal.getFirst().type()).isEqualTo(AgentEventType.SESSION_IDLE);
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
    void closeSessionEmitsRemainingDeltaWithoutInventingFinalMessage() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties, scheduler);
        AtomicLong sequence = new AtomicLong(30);

        try {
            assertThat(aggregator.accept(delta("pending", 7), sequence::incrementAndGet, ignored -> {
            })).isEmpty();

            List<AgentEvent> flushed = aggregator.closeSession("session-1", sequence::incrementAndGet, ignored -> {
            });

            assertThat(flushed).hasSize(1);
            assertThat(flushed).extracting(AgentEvent::type)
                    .containsExactly(AgentEventType.AGENT_MESSAGE_DELTA);
            assertThat(flushed).extracting(AgentEvent::seq)
                    .containsExactly(31L);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void flushesPendingDeltaBeforeSingleFinalAgentMessage() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties, scheduler);
        AtomicLong sequence = new AtomicLong(40);

        try {
            assertThat(aggregator.accept(delta("hello", 100), sequence::incrementAndGet, ignored -> {
            })).isEmpty();
            List<AgentEvent> finalized = aggregator.accept(finalMessage("hello world", 101),
                    sequence::incrementAndGet, ignored -> {
                    });
            List<AgentEvent> duplicateFinal = aggregator.accept(finalMessage("hello world", 102),
                    sequence::incrementAndGet, ignored -> {
                    });

            assertThat(finalized).hasSize(2);
            assertThat(finalized).extracting(AgentEvent::type)
                    .containsExactly(AgentEventType.AGENT_MESSAGE_DELTA, AgentEventType.AGENT_MESSAGE);
            assertThat(finalized).extracting(AgentEvent::seq)
                    .containsExactly(41L, 42L);
            assertThat(((AgentMessagePayload) finalized.get(1).payload()).content()).isEqualTo("hello world");
            assertThat(duplicateFinal).isEmpty();
        } finally {
            scheduler.shutdownNow();
        }
    }

    private AgentEvent delta(String content, long seq) {
        return new AgentEvent(null, "trace-1", 1L, 11L, "device-1", "project-1",
                "session-1", seq, AgentType.CODEX, AgentEventType.AGENT_MESSAGE_DELTA, null, null,
                new AgentMessagePayload("msg-1", "assistant", content, true, Map.of()), Map.of());
    }

    private AgentEvent finalMessage(String content, long seq) {
        return new AgentEvent(null, "trace-1", 1L, 11L, "device-1", "project-1",
                "session-1", seq, AgentType.CODEX, AgentEventType.AGENT_MESSAGE, null, null,
                new AgentMessagePayload("msg-1", "assistant", content, false, Map.of()), Map.of());
    }

    private AgentEvent idle() {
        return new AgentEvent(null, "trace-1", 1L, 11L, "device-1", "project-1",
                "session-1", 99, AgentType.CODEX, AgentEventType.SESSION_IDLE, null, null,
                new SessionPayload("native-1", AgentSessionStatus.IDLE, null, Map.of()), Map.of());
    }

}
