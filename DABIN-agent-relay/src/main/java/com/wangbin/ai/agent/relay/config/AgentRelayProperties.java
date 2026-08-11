package com.wangbin.ai.agent.relay.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "agent.relay")
public class AgentRelayProperties {

    private String nodeId = "relay-" + UUID.randomUUID();

    @Min(1)
    private int outboundQueueCapacity = 1024;

    private String websocketPath = "/agent/ws";

    private Duration helloTimeout = Duration.ofSeconds(10);

    private Duration heartbeatInterval = Duration.ofSeconds(20);

    private Duration heartbeatTimeout = Duration.ofSeconds(60);

    private Duration presenceTtl = Duration.ofSeconds(90);

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public int getOutboundQueueCapacity() {
        return outboundQueueCapacity;
    }

    public void setOutboundQueueCapacity(int outboundQueueCapacity) {
        this.outboundQueueCapacity = outboundQueueCapacity;
    }

    public String getWebsocketPath() {
        return websocketPath;
    }

    public void setWebsocketPath(String websocketPath) {
        this.websocketPath = websocketPath;
    }

    public Duration getHelloTimeout() {
        return helloTimeout;
    }

    public void setHelloTimeout(Duration helloTimeout) {
        this.helloTimeout = helloTimeout;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    public void setHeartbeatTimeout(Duration heartbeatTimeout) {
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public Duration getPresenceTtl() {
        return presenceTtl;
    }

    public void setPresenceTtl(Duration presenceTtl) {
        this.presenceTtl = presenceTtl;
    }

}
