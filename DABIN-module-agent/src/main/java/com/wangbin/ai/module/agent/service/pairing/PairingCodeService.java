package com.wangbin.ai.module.agent.service.pairing;

import com.wangbin.ai.agent.contract.coordination.PairingCodePayload;

public interface PairingCodeService {

    PairingCodePayload createPairingCode(Long tenantId, Long userId);

    PairingCodePayload consumePairingCode(String pairingCode);
}
