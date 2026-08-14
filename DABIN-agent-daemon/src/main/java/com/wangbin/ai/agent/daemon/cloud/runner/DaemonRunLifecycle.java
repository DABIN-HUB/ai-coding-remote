package com.wangbin.ai.agent.daemon.cloud.runner;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;

/**
 * Keeps explicit cloud RUN mode alive for the local execution process. The daemon is a non-web Spring Boot
 * application, so RUN mode needs one named lifecycle thread after Relay startup. The thread only waits for shutdown
 * and is only created when the daemon has a credential; no-credential startup still exits normally.
 */
@Component
public class DaemonRunLifecycle {

    private final CountDownLatch stopLatch = new CountDownLatch(1);
    private final Object monitor = new Object();
    private volatile Thread lifecycleThread;

    public void start() {
        synchronized (monitor) {
            if (lifecycleThread != null) {
                return;
            }
            Thread thread = new Thread(this::awaitStop, "agent-daemon-run-lifecycle");
            thread.setDaemon(false);
            thread.start();
            lifecycleThread = thread;
        }
    }

    public boolean isRunning() {
        Thread thread = lifecycleThread;
        return thread != null && thread.isAlive();
    }

    private void awaitStop() {
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
