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
    private final Cloud cloud = new Cloud();

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

    public Cloud getCloud() {
        return cloud;
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

    public static class Cloud {

        private String controlPlaneUrl = "http://127.0.0.1:48080";

        private String relayUrl = "ws://127.0.0.1:48180/agent/ws";

        private Duration reconnectInitialDelay = Duration.ofSeconds(1);

        private Duration reconnectMaxDelay = Duration.ofSeconds(30);

        public String getControlPlaneUrl() {
            return controlPlaneUrl;
        }

        public void setControlPlaneUrl(String controlPlaneUrl) {
            this.controlPlaneUrl = controlPlaneUrl;
        }

        public String getRelayUrl() {
            return relayUrl;
        }

        public void setRelayUrl(String relayUrl) {
            this.relayUrl = relayUrl;
        }

        public Duration getReconnectInitialDelay() {
            return reconnectInitialDelay;
        }

        public void setReconnectInitialDelay(Duration reconnectInitialDelay) {
            this.reconnectInitialDelay = reconnectInitialDelay;
        }

        public Duration getReconnectMaxDelay() {
            return reconnectMaxDelay;
        }

        public void setReconnectMaxDelay(Duration reconnectMaxDelay) {
            this.reconnectMaxDelay = reconnectMaxDelay;
        }
    }

}
