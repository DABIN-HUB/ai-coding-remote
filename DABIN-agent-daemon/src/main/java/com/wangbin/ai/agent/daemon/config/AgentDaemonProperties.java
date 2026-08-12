package com.wangbin.ai.agent.daemon.config;

import com.wangbin.ai.agent.contract.enums.AgentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "agent.daemon")
public class AgentDaemonProperties {

    @Min(1)
    private int processIoThreads = 4;

    private Duration eventAggregationWindow = Duration.ofMillis(100);

    @Min(1)
    private int eventAggregationMaxChars = 160;

    @Min(1)
    private int commandDedupCapacity = 2048;

    @Min(1)
    private int sessionControlIntentCapacity = 128;

    private Duration commandDedupTtl = Duration.ofMinutes(45);

    @Min(1)
    private int outboundQueueCapacity = 1024;

    @Min(1)
    private int reliableOutboundCapacity = 512;

    private final Smoke smoke = new Smoke();
    private final Cloud cloud = new Cloud();
    private final Artifact artifact = new Artifact();
    @Valid
    private final List<Project> projects = new ArrayList<>();

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

    public int getCommandDedupCapacity() {
        return commandDedupCapacity;
    }

    public void setCommandDedupCapacity(int commandDedupCapacity) {
        this.commandDedupCapacity = commandDedupCapacity;
    }

    public int getSessionControlIntentCapacity() {
        return sessionControlIntentCapacity;
    }

    public void setSessionControlIntentCapacity(int sessionControlIntentCapacity) {
        this.sessionControlIntentCapacity = sessionControlIntentCapacity;
    }

    public Duration getCommandDedupTtl() {
        return commandDedupTtl;
    }

    public void setCommandDedupTtl(Duration commandDedupTtl) {
        this.commandDedupTtl = commandDedupTtl;
    }

    public int getOutboundQueueCapacity() {
        return outboundQueueCapacity;
    }

    public void setOutboundQueueCapacity(int outboundQueueCapacity) {
        this.outboundQueueCapacity = outboundQueueCapacity;
    }

    public int getReliableOutboundCapacity() {
        return reliableOutboundCapacity;
    }

    public void setReliableOutboundCapacity(int reliableOutboundCapacity) {
        this.reliableOutboundCapacity = reliableOutboundCapacity;
    }

    public Smoke getSmoke() {
        return smoke;
    }

    public Cloud getCloud() {
        return cloud;
    }

    public Artifact getArtifact() {
        return artifact;
    }

    public List<Project> getProjects() {
        return projects;
    }

    @AssertTrue(message = "reliableOutboundCapacity must be less than or equal to outboundQueueCapacity")
    public boolean isReliableOutboundCapacityValid() {
        return reliableOutboundCapacity <= outboundQueueCapacity;
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

        private Duration welcomeTimeout = Duration.ofSeconds(10);

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

        public Duration getWelcomeTimeout() {
            return welcomeTimeout;
        }

        public void setWelcomeTimeout(Duration welcomeTimeout) {
            this.welcomeTimeout = welcomeTimeout;
        }
    }

    public static class Artifact {

        @Min(1)
        private long maxFileSize = 100L * 1024L * 1024L;

        @Min(1)
        private int transferThreads = 2;

        @Min(1)
        private int transferQueueCapacity = 16;

        public long getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(long maxFileSize) {
            this.maxFileSize = maxFileSize;
        }

        public int getTransferThreads() {
            return transferThreads;
        }

        public void setTransferThreads(int transferThreads) {
            this.transferThreads = transferThreads;
        }

        public int getTransferQueueCapacity() {
            return transferQueueCapacity;
        }

        public void setTransferQueueCapacity(int transferQueueCapacity) {
            this.transferQueueCapacity = transferQueueCapacity;
        }
    }

    public static class Project {

        private String localProjectId;

        private String projectName;

        private String workspacePath;

        private AgentType agentType = AgentType.CODEX;

        public String getLocalProjectId() {
            return localProjectId;
        }

        public void setLocalProjectId(String localProjectId) {
            this.localProjectId = localProjectId;
        }

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public String getWorkspacePath() {
            return workspacePath;
        }

        public void setWorkspacePath(String workspacePath) {
            this.workspacePath = workspacePath;
        }

        public AgentType getAgentType() {
            return agentType;
        }

        public void setAgentType(AgentType agentType) {
            this.agentType = agentType;
        }
    }

}
