package com.wangbin.ai.module.agent.service.device;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.module.agent.controller.admin.device.vo.*;

public interface AgentDeviceService {

    AgentPairingCodeRespVO createPairingCode(Long tenantId, Long userId);

    PageResult<AgentDeviceRespVO> getDevicePage(AgentDevicePageReqVO reqVO, Long userId);

    AgentDeviceRespVO getDevice(Long id, Long userId);

    AgentDevicePairRespVO pairDevice(AgentDevicePairReqVO reqVO);

    AgentDeviceRelayTicketRespVO createDeviceRelayTicket(Long tenantId, String credentialId, String credentialSecret);
}
