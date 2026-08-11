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

    @Test
    void serializesSequenceAssignmentAndSinkEmissionPerSession() throws Exception {
        SerializedSessionEventEmitter emitter = new SerializedSessionEventEmitter();
        AtomicLong sequence = new AtomicLong();
        List<AgentEvent> observed = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(20);
        var executor = Executors.newFixedThreadPool(4);

        try {
            for (int i = 0; i < 20; i++) {
                int index = i;
                executor.submit(() -> {
                    try {
                        start.await();
                        emitter.emit(event("session-a", index), sequence::incrementAndGet, observed::add);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(observed).hasSize(20);
            assertThat(observed).extracting(AgentEvent::seq)
                    .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 20)
                            .boxed().toList());
        } finally {
            executor.shutdownNow();
        }
    }

    private AgentEvent event(String sessionId, int index) {
        return new AgentEvent(null, "trace-" + index, 1L, 11L, "device-1", "project-1",
                sessionId, 0, AgentType.CODEX, AgentEventType.SESSION_STATE_CHANGED, null, null,
                new SessionPayload("native-1", AgentSessionStatus.RUNNING, null, Map.of()), Map.of());
    }
}
