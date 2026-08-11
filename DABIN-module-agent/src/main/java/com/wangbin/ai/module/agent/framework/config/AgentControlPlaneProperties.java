package com.wangbin.ai.module.agent.framework.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "agent.control-plane")
public class AgentControlPlaneProperties {

    @NotNull
    private Duration pairingCodeTtl = Duration.ofMinutes(5);

    @NotNull
    private Duration relayTicketTtl = Duration.ofSeconds(60);

    @Positive
    private int pairingCodeCreateMaxRetries = 5;

    @NotNull
    private Duration pairingLockWaitTime = Duration.ofSeconds(3);

    @NotNull
    private Duration commandIdempotencyLockWaitTime = Duration.ofSeconds(3);

    @NotNull
    private Duration commandAckTimeout = Duration.ofSeconds(30);

    @Positive
    private long eventIngressStreamMaxLen = 10000;

    @NotNull
    private String eventIngressConsumerGroup = "agent-control-plane";

    @NotNull
    private String eventIngressConsumerNamePrefix = "agent-control-plane-";

    @Positive
    private int eventIngressBatchSize = 50;

    @NotNull
    private Duration eventIngressReadBlockTime = Duration.ofMillis(100);

    @Positive
    private long eventIngressPollIntervalMillis = 1000;

    public Duration getPairingCodeTtl() {
        return pairingCodeTtl;
    }

    public void setPairingCodeTtl(Duration pairingCodeTtl) {
        this.pairingCodeTtl = pairingCodeTtl;
    }

    public Duration getRelayTicketTtl() {
        return relayTicketTtl;
    }

    public void setRelayTicketTtl(Duration relayTicketTtl) {
        this.relayTicketTtl = relayTicketTtl;
    }

    public int getPairingCodeCreateMaxRetries() {
        return pairingCodeCreateMaxRetries;
    }

    public void setPairingCodeCreateMaxRetries(int pairingCodeCreateMaxRetries) {
        this.pairingCodeCreateMaxRetries = pairingCodeCreateMaxRetries;
    }

    public Duration getPairingLockWaitTime() {
        return pairingLockWaitTime;
    }

    public void setPairingLockWaitTime(Duration pairingLockWaitTime) {
        this.pairingLockWaitTime = pairingLockWaitTime;
    }

    public Duration getCommandIdempotencyLockWaitTime() {
        return commandIdempotencyLockWaitTime;
    }

    public void setCommandIdempotencyLockWaitTime(Duration commandIdempotencyLockWaitTime) {
        this.commandIdempotencyLockWaitTime = commandIdempotencyLockWaitTime;
    }

    public Duration getCommandAckTimeout() {
        return commandAckTimeout;
    }

    public void setCommandAckTimeout(Duration commandAckTimeout) {
        this.commandAckTimeout = commandAckTimeout;
    }

    public long getEventIngressStreamMaxLen() {
        return eventIngressStreamMaxLen;
    }

    public void setEventIngressStreamMaxLen(long eventIngressStreamMaxLen) {
        this.eventIngressStreamMaxLen = eventIngressStreamMaxLen;
    }

    public String getEventIngressConsumerGroup() {
        return eventIngressConsumerGroup;
    }

    public void setEventIngressConsumerGroup(String eventIngressConsumerGroup) {
        this.eventIngressConsumerGroup = eventIngressConsumerGroup;
    }

    public String getEventIngressConsumerNamePrefix() {
        return eventIngressConsumerNamePrefix;
    }

    public void setEventIngressConsumerNamePrefix(String eventIngressConsumerNamePrefix) {
        this.eventIngressConsumerNamePrefix = eventIngressConsumerNamePrefix;
    }

    public int getEventIngressBatchSize() {
        return eventIngressBatchSize;
    }

    public void setEventIngressBatchSize(int eventIngressBatchSize) {
        this.eventIngressBatchSize = eventIngressBatchSize;
    }

    public Duration getEventIngressReadBlockTime() {
        return eventIngressReadBlockTime;
    }

    public void setEventIngressReadBlockTime(Duration eventIngressReadBlockTime) {
        this.eventIngressReadBlockTime = eventIngressReadBlockTime;
    }

    public long getEventIngressPollIntervalMillis() {
        return eventIngressPollIntervalMillis;
    }

    public void setEventIngressPollIntervalMillis(long eventIngressPollIntervalMillis) {
        this.eventIngressPollIntervalMillis = eventIngressPollIntervalMillis;
    }
}
