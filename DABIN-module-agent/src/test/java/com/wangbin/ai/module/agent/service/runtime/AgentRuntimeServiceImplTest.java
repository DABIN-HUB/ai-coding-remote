package com.wangbin.ai.module.agent.service.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.runtime.AgentRuntimeTypes;
import com.wangbin.ai.agent.contract.session.AgentCapabilities;
import com.wangbin.ai.module.agent.controller.admin.runtime.vo.AgentRuntimeReportReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.dataobject.runtime.AgentRuntimeDO;
import com.wangbin.ai.module.agent.dal.mysql.runtime.AgentRuntimeMapper;
import com.wangbin.ai.module.agent.enums.RuntimeStatus;
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import com.wangbin.ai.module.agent.service.device.DeviceCredentialAuthService;
import com.wangbin.ai.module.agent.service.device.DeviceCredentialIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeServiceImplTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final Long TEST_DEVICE_DB_ID = 10L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_CREDENTIAL_ID = "cred-1";
    private static final String TEST_CREDENTIAL_SECRET = "secret";
    private static final String TEST_RUNTIME_ID = "rt-1";
    private static final String TEST_VERSION = "1.2.3";
    private static final String TEST_EXECUTABLE_PATH = "D:/tools/codex.exe";

    private final AgentRuntimeMapper runtimeMapper = mock(AgentRuntimeMapper.class);
    private final DeviceCredentialAuthService credentialAuthService = mock(DeviceCredentialAuthService.class);
    private final AgentIdFactory idFactory = mock(AgentIdFactory.class);
    private AgentRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentRuntimeServiceImpl(runtimeMapper, credentialAuthService, idFactory, new ObjectMapper());
        when(credentialAuthService.authenticate(TEST_TENANT_ID, TEST_CREDENTIAL_ID, TEST_CREDENTIAL_SECRET))
                .thenReturn(identity());
    }

    @Test
    void reportRuntimeCreatesAvailableRuntimeAndSerializesTypedCapabilities() {
        when(runtimeMapper.selectByDeviceAndType(TEST_DEVICE_DB_ID, AgentRuntimeTypes.CODEX_APP_SERVER))
                .thenReturn(null);
        when(idFactory.runtimeId()).thenReturn(TEST_RUNTIME_ID);

        service.reportRuntime(TEST_TENANT_ID, TEST_CREDENTIAL_ID, TEST_CREDENTIAL_SECRET, reqVO());

        ArgumentCaptor<AgentRuntimeDO> runtimeCaptor = ArgumentCaptor.forClass(AgentRuntimeDO.class);
        verify(runtimeMapper).insert(runtimeCaptor.capture());
        AgentRuntimeDO runtime = runtimeCaptor.getValue();
        assertThat(runtime.getTenantId()).isEqualTo(TEST_TENANT_ID);
        assertThat(runtime.getDeviceId()).isEqualTo(TEST_DEVICE_DB_ID);
        assertThat(runtime.getRuntimeId()).isEqualTo(TEST_RUNTIME_ID);
        assertThat(runtime.getRuntimeType()).isEqualTo(AgentRuntimeTypes.CODEX_APP_SERVER);
        assertThat(runtime.getRuntimeStatus()).isEqualTo(RuntimeStatus.AVAILABLE.name());
        assertThat(runtime.getCapabilitiesJson()).contains("\"prompt\":true", "\"permission\":false");
    }

    @Test
    void reportRuntimeUpdatesExistingDeviceRuntime() {
        AgentRuntimeDO existing = new AgentRuntimeDO();
        existing.setId(100L);
        existing.setTenantId(TEST_TENANT_ID);
        existing.setDeviceId(TEST_DEVICE_DB_ID);
        existing.setRuntimeId(TEST_RUNTIME_ID);
        existing.setRuntimeType(AgentRuntimeTypes.CODEX_APP_SERVER);
        existing.setRuntimeStatus(RuntimeStatus.UNAVAILABLE.name());
        when(runtimeMapper.selectByDeviceAndType(TEST_DEVICE_DB_ID, AgentRuntimeTypes.CODEX_APP_SERVER))
                .thenReturn(existing);

        service.reportRuntime(TEST_TENANT_ID, TEST_CREDENTIAL_ID, TEST_CREDENTIAL_SECRET, reqVO());

        verify(runtimeMapper).updateById(existing);
        assertThat(existing.getRuntimeVersion()).isEqualTo(TEST_VERSION);
        assertThat(existing.getExecutablePath()).isEqualTo(TEST_EXECUTABLE_PATH);
        assertThat(existing.getRuntimeStatus()).isEqualTo(RuntimeStatus.AVAILABLE.name());
    }

    private DeviceCredentialIdentity identity() {
        AgentDeviceDO device = new AgentDeviceDO();
        device.setId(TEST_DEVICE_DB_ID);
        device.setTenantId(TEST_TENANT_ID);
        device.setDeviceId(TEST_DEVICE_ID);
        device.setOwnerUserId(TEST_USER_ID);
        return new DeviceCredentialIdentity(TEST_TENANT_ID, TEST_USER_ID, device);
    }

    private AgentRuntimeReportReqVO reqVO() {
        AgentRuntimeReportReqVO reqVO = new AgentRuntimeReportReqVO();
        reqVO.setAgentType(AgentType.CODEX);
        reqVO.setRuntimeType(AgentRuntimeTypes.CODEX_APP_SERVER);
        reqVO.setRuntimeVersion(TEST_VERSION);
        reqVO.setExecutablePath(TEST_EXECUTABLE_PATH);
        reqVO.setCapabilities(AgentCapabilities.codexDefault());
        return reqVO;
    }
}
