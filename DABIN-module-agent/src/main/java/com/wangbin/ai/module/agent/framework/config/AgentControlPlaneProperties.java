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
}
