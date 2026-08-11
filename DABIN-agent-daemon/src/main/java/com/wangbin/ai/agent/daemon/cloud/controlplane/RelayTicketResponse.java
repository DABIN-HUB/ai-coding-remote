package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RelayTicketResponse(String ticket, Instant expireAt) {
}
