package com.wangbin.ai.agent.daemon.cloud.controlplane;

import java.time.Instant;

public record RelayTicketResponse(String ticket, Instant expireAt) {
}
