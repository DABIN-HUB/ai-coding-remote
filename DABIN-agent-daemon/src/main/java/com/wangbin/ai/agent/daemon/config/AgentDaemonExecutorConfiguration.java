package com.wangbin.ai.agent.daemon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration(proxyBeanMethods = false)
public class AgentDaemonExecutorConfiguration {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentProcessIoExecutor(AgentDaemonProperties properties) {
        return Executors.newFixedThreadPool(properties.getProcessIoThreads(),
                namedThreadFactory("agent-process-io-"));
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService agentEventScheduler() {
        return Executors.newSingleThreadScheduledExecutor(namedThreadFactory("agent-event-scheduler-"));
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

}
