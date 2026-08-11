package com.wangbin.ai.agent.daemon.cloud.runner;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;

/**
 * Keeps explicit cloud RUN mode alive for the local execution process. The
 * daemon is a non-web Spring Boot application, so the ApplicationRunner must
 * block after starting the outbound WSS loop; PAIR and smoke/no-mode startup
 * still exit normally.
 */
@Component
public class DaemonRunLifecycle {

    private final CountDownLatch stopLatch = new CountDownLatch(1);

    public void awaitStop() {
        try {
            stopLatch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        stopLatch.countDown();
    }
}
