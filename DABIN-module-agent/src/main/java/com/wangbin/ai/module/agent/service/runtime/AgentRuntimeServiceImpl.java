package com.wangbin.ai.module.agent.service.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.module.agent.controller.admin.runtime.vo.AgentRuntimeReportReqVO;
import com.wangbin.ai.module.agent.controller.admin.runtime.vo.AgentRuntimeRespVO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.dataobject.runtime.AgentRuntimeDO;
import com.wangbin.ai.module.agent.dal.mysql.runtime.AgentRuntimeMapper;
import com.wangbin.ai.module.agent.enums.RuntimeStatus;
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import com.wangbin.ai.module.agent.service.device.DeviceCredentialAuthService;
import com.wangbin.ai.module.agent.service.device.DeviceCredentialIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.RUNTIME_UNAVAILABLE;

@Service
@RequiredArgsConstructor
public class AgentRuntimeServiceImpl implements AgentRuntimeService {

    private final AgentRuntimeMapper runtimeMapper;
    private final DeviceCredentialAuthService credentialAuthService;
    private final AgentIdFactory idFactory;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRuntimeRespVO reportRuntime(Long tenantId, String credentialId, String credentialSecret,
                                            AgentRuntimeReportReqVO reqVO) {
        DeviceCredentialIdentity identity = credentialAuthService.authenticate(tenantId, credentialId, credentialSecret);
        AgentDeviceDO device = identity.device();
        AgentRuntimeDO runtime = runtimeMapper.selectByDeviceAndType(device.getId(), reqVO.getRuntimeType());
        if (runtime == null) {
            runtime = new AgentRuntimeDO();
            runtime.setTenantId(identity.tenantId());
            runtime.setDeviceId(device.getId());
            runtime.setRuntimeId(reqVO.getRuntimeId() == null || reqVO.getRuntimeId().isBlank()
                    ? idFactory.runtimeId() : reqVO.getRuntimeId());
            runtime.setRuntimeType(reqVO.getRuntimeType());
            runtime.setCreator(String.valueOf(identity.ownerUserId()));
        }
        runtime.setRuntimeVersion(reqVO.getRuntimeVersion());
        runtime.setExecutablePath(reqVO.getExecutablePath());
        runtime.setRuntimeStatus(RuntimeStatus.AVAILABLE.name());
        runtime.setCapabilitiesJson(writeCapabilities(reqVO));
        runtime.setLastDiscoveredTime(LocalDateTime.now());
        runtime.setUpdater(String.valueOf(identity.ownerUserId()));
        if (runtime.getId() == null) {
            runtimeMapper.insert(runtime);
        } else {
            runtimeMapper.updateById(runtime);
        }
        return toRespVO(runtime);
    }

    private String writeCapabilities(AgentRuntimeReportReqVO reqVO) {
        try {
            return objectMapper.writeValueAsString(reqVO.getCapabilities());
        } catch (JsonProcessingException ex) {
            throw exception(RUNTIME_UNAVAILABLE);
        }
    }

    private AgentRuntimeRespVO toRespVO(AgentRuntimeDO runtime) {
        AgentRuntimeRespVO respVO = new AgentRuntimeRespVO();
        respVO.setId(runtime.getId());
        respVO.setDeviceId(runtime.getDeviceId());
        respVO.setRuntimeId(runtime.getRuntimeId());
        respVO.setRuntimeType(runtime.getRuntimeType());
        respVO.setRuntimeVersion(runtime.getRuntimeVersion());
        respVO.setExecutablePath(runtime.getExecutablePath());
        respVO.setRuntimeStatus(runtime.getRuntimeStatus());
        respVO.setCapabilitiesJson(runtime.getCapabilitiesJson());
        respVO.setLastDiscoveredTime(runtime.getLastDiscoveredTime());
        return respVO;
    }
}
