package com.wangbin.ai.agent.contract.coordination;

import java.time.Instant;

public record RelayTicketPayload(
        String ticket,
        RelaySubjectType subjectType,
        Long tenantId,
        Long userId,
        String deviceId,
        Instant issuedAt,
        Instant expireAt
) {
}
