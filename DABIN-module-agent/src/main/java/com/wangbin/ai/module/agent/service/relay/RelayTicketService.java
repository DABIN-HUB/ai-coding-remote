package com.wangbin.ai.module.agent.service.relay;

import com.wangbin.ai.agent.contract.coordination.RelayTicketPayload;

public interface RelayTicketService {

    RelayTicketPayload createUserTicket(Long tenantId, Long userId);

    RelayTicketPayload createDeviceTicket(Long tenantId, Long userId, String deviceId);
}
