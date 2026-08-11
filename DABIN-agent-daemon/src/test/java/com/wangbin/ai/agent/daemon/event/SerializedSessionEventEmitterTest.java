package com.wangbin.ai.agent.daemon.event;

import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SerializedSessionEventEmitterTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final String SESSION_A = "session-a";
    private static final String DEVICE_ID = "device-1";
    private static final String PROJECT_ID = "project-1";
    private static final String TRACE_PREFIX = "trace-";
    private static final String NATIVE_SESSION_ID = "native-1";
    private static final int EVENT_COUNT = 20;
    private static final int WORKER_THREADS = 4;
    private static final long AWAIT_TIMEOUT_SECONDS = 1L;

    @Test
    void serializesSequenceAssignmentAndSinkEmissionPerSession() throws Exception {
        SerializedSessionEventEmitter emitter = new SerializedSessionEventEmitter();
        AtomicLong sequence = new AtomicLong();
        List<AgentEvent> observed = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(EVENT_COUNT);
        var executor = Executors.newFixedThreadPool(WORKER_THREADS);

        try {
            for (int i = 0; i < EVENT_COUNT; i++) {
                int index = i;
                executor.submit(() -> {
                    try {
                        start.await();
                        emitter.emit(event(SESSION_A, index), sequence::incrementAndGet, observed::add);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThat(observed).hasSize(EVENT_COUNT);
            assertThat(observed).extracting(AgentEvent::seq)
                    .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, EVENT_COUNT)
                            .boxed().toList());
        } finally {
            executor.shutdownNow();
        }
    }

    private AgentEvent event(String sessionId, int index) {
        return new AgentEvent(null, TRACE_PREFIX + index, TEST_TENANT_ID, TEST_USER_ID, DEVICE_ID, PROJECT_ID,
                sessionId, 0, AgentType.CODEX, AgentEventType.SESSION_STATE_CHANGED, null, null,
                new SessionPayload(NATIVE_SESSION_ID, AgentSessionStatus.RUNNING, null, Map.of()), Map.of());
    }
}
