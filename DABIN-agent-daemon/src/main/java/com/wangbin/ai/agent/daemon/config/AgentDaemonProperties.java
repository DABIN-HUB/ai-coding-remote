package com.wangbin.ai.agent.daemon.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "agent.daemon")
public class AgentDaemonProperties {

    @Min(1)
    private int processIoThreads = 4;

    private Duration eventAggregationWindow = Duration.ofMillis(100);

    @Min(1)
    private int eventAggregationMaxChars = 160;

    private final Smoke smoke = new Smoke();

    public int getProcessIoThreads() {
        return processIoThreads;
    }

    public void setProcessIoThreads(int processIoThreads) {
        this.processIoThreads = processIoThreads;
    }

    public Duration getEventAggregationWindow() {
        return eventAggregationWindow;
    }

    public void setEventAggregationWindow(Duration eventAggregationWindow) {
        this.eventAggregationWindow = eventAggregationWindow;
    }

    public int getEventAggregationMaxChars() {
        return eventAggregationMaxChars;
    }

    public void setEventAggregationMaxChars(int eventAggregationMaxChars) {
        this.eventAggregationMaxChars = eventAggregationMaxChars;
    }

    public Smoke getSmoke() {
        return smoke;
    }

    public static class Smoke {

        private Duration timeout = Duration.ofMinutes(5);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

    }

}
