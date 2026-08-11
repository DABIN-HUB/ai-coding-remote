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

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final String TRACE_ID = "trace-1";
    private static final String DEVICE_ID = "device-1";
    private static final String PROJECT_ID = "project-1";
    private static final String DEFAULT_SESSION_ID = "session-1";
    private static final String SESSION_A = "session-a";
    private static final String SESSION_B = "session-b";
    private static final String MESSAGE_ID = "msg-1";
    private static final String MESSAGE_A = "msg-a";
    private static final String MESSAGE_B = "msg-b";
    private static final String MESSAGE_ROLE_ASSISTANT = "assistant";
    private static final String NATIVE_SESSION_ID = "native-1";
    private static final java.time.Duration FAST_AGGREGATION_WINDOW = java.time.Duration.ofMillis(20);
    private static final int SMALL_AGGREGATION_MAX_CHARS = 5;
    private static final int LARGE_AGGREGATION_MAX_CHARS = 100;
    private static final long FIRST_SEQUENCE_BASE = 10L;
    private static final long TIMER_SEQUENCE_BASE = 20L;
    private static final long CLOSE_SEQUENCE_BASE = 30L;
    private static final long FINAL_SEQUENCE_BASE = 40L;
    private static final long IDLE_INPUT_SEQUENCE = 99L;
    private static final long AWAIT_TIMEOUT_SECONDS = 1L;

    @Test
    void keepsIntermediateFlushesAsDeltaAndFinalizesOnlyOnCompletedMessage() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setEventAggregationMaxChars(SMALL_AGGREGATION_MAX_CHARS);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties, scheduler);
        AtomicLong sequence = new AtomicLong(FIRST_SEQUENCE_BASE);

        try {
            List<AgentEvent> firstFlush = aggregator.accept(delta("hello", 1), sequence::incrementAndGet, ignored -> {
            });
            assertThat(firstFlush).hasSize(1);
            assertThat(firstFlush.getFirst().type()).isEqualTo(AgentEventType.AGENT_MESSAGE_DELTA);
            assertThat(((AgentMessagePayload) firstFlush.getFirst().payload()).content()).isEqualTo("hello");
            assertThat(firstFlush.getFirst().seq()).isZero();

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
            assertThat(finalized.getFirst().seq()).isZero();

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
        properties.setEventAggregationWindow(FAST_AGGREGATION_WINDOW);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties, scheduler);
        AtomicLong sequence = new AtomicLong(TIMER_SEQUENCE_BASE);
        CountDownLatch latch = new CountDownLatch(1);
        List<AgentEvent> flushed = new java.util.concurrent.CopyOnWriteArrayList<>();

        try {
            assertThat(aggregator.accept(delta("hello", 1), sequence::incrementAndGet, event -> {
                flushed.add(event);
                latch.countDown();
            })).isEmpty();

            assertThat(latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(flushed).hasSize(1);
            assertThat(flushed.getFirst().type()).isEqualTo(AgentEventType.AGENT_MESSAGE_DELTA);
            assertThat(flushed.getFirst().seq()).isZero();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void closeSessionEmitsRemainingDeltaWithoutInventingFinalMessage() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties, scheduler);
        AtomicLong sequence = new AtomicLong(CLOSE_SEQUENCE_BASE);

        try {
            assertThat(aggregator.accept(delta("pending", 7), sequence::incrementAndGet, ignored -> {
            })).isEmpty();

            List<AgentEvent> flushed = aggregator.closeSession(DEFAULT_SESSION_ID, sequence::incrementAndGet, ignored -> {
            });

            assertThat(flushed).hasSize(1);
            assertThat(flushed).extracting(AgentEvent::type)
                    .containsExactly(AgentEventType.AGENT_MESSAGE_DELTA);
            assertThat(flushed).extracting(AgentEvent::seq)
                    .containsExactly(0L);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void flushesPendingDeltaBeforeSingleFinalAgentMessage() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties, scheduler);
        AtomicLong sequence = new AtomicLong(FINAL_SEQUENCE_BASE);

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
                    .containsExactly(0L, 0L);
            assertThat(((AgentMessagePayload) finalized.get(1).payload()).content()).isEqualTo("hello world");
            assertThat(duplicateFinal).isEmpty();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void assignsContinuousSequenceOnlyToActuallyEmittedEventsPerSession() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setEventAggregationMaxChars(LARGE_AGGREGATION_MAX_CHARS);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DeltaEventAggregator aggregator = new DeltaEventAggregator(properties, scheduler);
        SerializedSessionEventEmitter emitter = new SerializedSessionEventEmitter();
        AtomicLong sessionASequence = new AtomicLong();
        AtomicLong sessionBSequence = new AtomicLong();
        List<AgentEvent> sessionAObserved = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<AgentEvent> sessionBObserved = new java.util.concurrent.CopyOnWriteArrayList<>();

        try {
            assertThat(aggregator.accept(delta("a", SESSION_A, MESSAGE_A), sessionASequence::incrementAndGet,
                    ignored -> {
                    })).isEmpty();
            List<AgentEvent> sessionAStarted = aggregator.accept(sessionState(SESSION_A),
                    sessionASequence::incrementAndGet, ignored -> {
                    });
            assertThat(aggregator.accept(delta("b", SESSION_A, MESSAGE_A), sessionASequence::incrementAndGet,
                    ignored -> {
                    })).isEmpty();
            List<AgentEvent> sessionAFinal = aggregator.accept(finalMessage("ab", SESSION_A, MESSAGE_A),
                    sessionASequence::incrementAndGet, ignored -> {
                    });
            List<AgentEvent> sessionAIdle = aggregator.accept(idle(SESSION_A), sessionASequence::incrementAndGet,
                    ignored -> {
                    });

            List<AgentEvent> sessionBFinal = aggregator.accept(finalMessage("done", SESSION_B, MESSAGE_B),
                    sessionBSequence::incrementAndGet, ignored -> {
                    });
            List<AgentEvent> sessionBIdle = aggregator.accept(idle(SESSION_B), sessionBSequence::incrementAndGet,
                    ignored -> {
                    });

            concat(sessionAStarted, sessionAFinal, sessionAIdle)
                    .forEach(event -> emitter.emit(event, sessionASequence::incrementAndGet, sessionAObserved::add));
            concat(sessionBFinal, sessionBIdle)
                    .forEach(event -> emitter.emit(event, sessionBSequence::incrementAndGet, sessionBObserved::add));

            assertThat(sessionAObserved)
                    .extracting(AgentEvent::seq)
                    .containsExactly(1L, 2L, 3L, 4L);
            assertThat(sessionBObserved)
                    .extracting(AgentEvent::seq)
                    .containsExactly(1L, 2L);
        } finally {
            scheduler.shutdownNow();
        }
    }

    private AgentEvent delta(String content, long seq) {
        return new AgentEvent(null, TRACE_ID, TEST_TENANT_ID, TEST_USER_ID, DEVICE_ID, PROJECT_ID,
                DEFAULT_SESSION_ID, seq, AgentType.CODEX, AgentEventType.AGENT_MESSAGE_DELTA, null, null,
                new AgentMessagePayload(MESSAGE_ID, MESSAGE_ROLE_ASSISTANT, content, true, Map.of()), Map.of());
    }

    private AgentEvent delta(String content, String sessionId, String messageId) {
        return new AgentEvent(null, TRACE_ID, TEST_TENANT_ID, TEST_USER_ID, DEVICE_ID, PROJECT_ID,
                sessionId, 0, AgentType.CODEX, AgentEventType.AGENT_MESSAGE_DELTA, null, null,
                new AgentMessagePayload(messageId, MESSAGE_ROLE_ASSISTANT, content, true, Map.of()), Map.of());
    }

    private AgentEvent finalMessage(String content, long seq) {
        return new AgentEvent(null, TRACE_ID, TEST_TENANT_ID, TEST_USER_ID, DEVICE_ID, PROJECT_ID,
                DEFAULT_SESSION_ID, seq, AgentType.CODEX, AgentEventType.AGENT_MESSAGE, null, null,
                new AgentMessagePayload(MESSAGE_ID, MESSAGE_ROLE_ASSISTANT, content, false, Map.of()), Map.of());
    }

    private AgentEvent finalMessage(String content, String sessionId, String messageId) {
        return new AgentEvent(null, TRACE_ID, TEST_TENANT_ID, TEST_USER_ID, DEVICE_ID, PROJECT_ID,
                sessionId, 0, AgentType.CODEX, AgentEventType.AGENT_MESSAGE, null, null,
                new AgentMessagePayload(messageId, MESSAGE_ROLE_ASSISTANT, content, false, Map.of()), Map.of());
    }

    private AgentEvent sessionState(String sessionId) {
        return new AgentEvent(null, TRACE_ID, TEST_TENANT_ID, TEST_USER_ID, DEVICE_ID, PROJECT_ID,
                sessionId, 0, AgentType.CODEX, AgentEventType.SESSION_STATE_CHANGED, null, null,
                new SessionPayload(NATIVE_SESSION_ID, AgentSessionStatus.RUNNING, null, Map.of()), Map.of());
    }

    private AgentEvent idle() {
        return new AgentEvent(null, TRACE_ID, TEST_TENANT_ID, TEST_USER_ID, DEVICE_ID, PROJECT_ID,
                DEFAULT_SESSION_ID, IDLE_INPUT_SEQUENCE, AgentType.CODEX, AgentEventType.SESSION_IDLE, null, null,
                new SessionPayload(NATIVE_SESSION_ID, AgentSessionStatus.IDLE, null, Map.of()), Map.of());
    }

    private AgentEvent idle(String sessionId) {
        return new AgentEvent(null, TRACE_ID, TEST_TENANT_ID, TEST_USER_ID, DEVICE_ID, PROJECT_ID,
                sessionId, 0, AgentType.CODEX, AgentEventType.SESSION_IDLE, null, null,
                new SessionPayload(NATIVE_SESSION_ID, AgentSessionStatus.IDLE, null, Map.of()), Map.of());
    }

    @SafeVarargs
    private final List<AgentEvent> concat(List<AgentEvent>... parts) {
        return java.util.Arrays.stream(parts)
                .flatMap(List::stream)
                .toList();
    }

}
