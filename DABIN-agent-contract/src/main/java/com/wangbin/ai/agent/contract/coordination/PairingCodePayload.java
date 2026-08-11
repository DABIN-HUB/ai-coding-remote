package com.wangbin.ai.agent.contract.coordination;

import java.time.Instant;

public record PairingCodePayload(
        String pairingCode,
        Long tenantId,
        Long userId,
        Instant createdAt,
        Instant expireAt
) {
}
