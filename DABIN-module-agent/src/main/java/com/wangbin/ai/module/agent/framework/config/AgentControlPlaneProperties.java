package com.wangbin.ai.module.agent.framework.config;

import jakarta.validation.constraints.NotNull;
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
}
