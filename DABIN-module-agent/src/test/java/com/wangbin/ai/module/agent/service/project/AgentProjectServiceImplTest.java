package com.wangbin.ai.module.agent.service.project;

import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.module.agent.controller.admin.project.vo.AgentProjectRegisterReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.dataobject.project.AgentProjectDO;
import com.wangbin.ai.module.agent.dal.mysql.project.AgentProjectMapper;
import com.wangbin.ai.module.agent.enums.ProjectStatus;
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import com.wangbin.ai.module.agent.service.device.DeviceCredentialAuthService;
import com.wangbin.ai.module.agent.service.device.DeviceCredentialIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentProjectServiceImplTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final Long TEST_DEVICE_DB_ID = 10L;
    private static final Long OTHER_DEVICE_DB_ID = 20L;
    private static final String TEST_CREDENTIAL_ID = "cred-1";
    private static final String TEST_CREDENTIAL_SECRET = "secret";
    private static final String TEST_LOCAL_PROJECT_ID = "local-1";
    private static final String TEST_PLATFORM_PROJECT_ID = "prj-1";
    private static final String TEST_PROJECT_NAME = "Project";
    private static final String TEST_WORKSPACE_PATH = "F:/workspace";
    private static final String TEST_WORKSPACE_REAL_PATH = "F:/workspace-real";

    private final AgentProjectMapper projectMapper = mock(AgentProjectMapper.class);
    private final DeviceCredentialAuthService credentialAuthService = mock(DeviceCredentialAuthService.class);
    private final AgentIdFactory idFactory = mock(AgentIdFactory.class);
    private AgentProjectServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentProjectServiceImpl(projectMapper, credentialAuthService, idFactory);
    }

    @Test
    void registerProjectCreatesByCredentialDeviceAndUpdatesSameLocalProject() {
        when(credentialAuthService.authenticate(TEST_TENANT_ID, TEST_CREDENTIAL_ID, TEST_CREDENTIAL_SECRET))
                .thenReturn(identity(TEST_DEVICE_DB_ID));
        when(idFactory.projectId()).thenReturn(TEST_PLATFORM_PROJECT_ID);
        when(projectMapper.selectByLocalProject(TEST_DEVICE_DB_ID, TEST_USER_ID, TEST_LOCAL_PROJECT_ID))
                .thenReturn(null);

        service.registerProject(TEST_TENANT_ID, TEST_CREDENTIAL_ID, TEST_CREDENTIAL_SECRET, reqVO(TEST_PROJECT_NAME));

        ArgumentCaptor<AgentProjectDO> insertCaptor = ArgumentCaptor.forClass(AgentProjectDO.class);
        verify(projectMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getTenantId()).isEqualTo(TEST_TENANT_ID);
        assertThat(insertCaptor.getValue().getDeviceId()).isEqualTo(TEST_DEVICE_DB_ID);
        assertThat(insertCaptor.getValue().getProjectId()).isEqualTo(TEST_PLATFORM_PROJECT_ID);
        assertThat(insertCaptor.getValue().getProjectStatus()).isEqualTo(ProjectStatus.ACTIVE.name());

        AgentProjectDO existing = insertCaptor.getValue();
        existing.setId(100L);
        when(projectMapper.selectByLocalProject(TEST_DEVICE_DB_ID, TEST_USER_ID, TEST_LOCAL_PROJECT_ID))
                .thenReturn(existing);
        service.registerProject(TEST_TENANT_ID, TEST_CREDENTIAL_ID, TEST_CREDENTIAL_SECRET, reqVO("Renamed"));

        verify(projectMapper).updateById(existing);
        assertThat(existing.getProjectName()).isEqualTo("Renamed");
    }

    @Test
    void sameLocalProjectIdOnDifferentDeviceCreatesIndependentProjectInsteadOfTakeover() {
        when(credentialAuthService.authenticate(TEST_TENANT_ID, TEST_CREDENTIAL_ID, TEST_CREDENTIAL_SECRET))
                .thenReturn(identity(OTHER_DEVICE_DB_ID));
        when(idFactory.projectId()).thenReturn("prj-2");
        when(projectMapper.selectByLocalProject(OTHER_DEVICE_DB_ID, TEST_USER_ID, TEST_LOCAL_PROJECT_ID))
                .thenReturn(null);

        service.registerProject(TEST_TENANT_ID, TEST_CREDENTIAL_ID, TEST_CREDENTIAL_SECRET, reqVO(TEST_PROJECT_NAME));

        ArgumentCaptor<AgentProjectDO> insertCaptor = ArgumentCaptor.forClass(AgentProjectDO.class);
        verify(projectMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getDeviceId()).isEqualTo(OTHER_DEVICE_DB_ID);
        verify(projectMapper, never()).updateById(any(AgentProjectDO.class));
    }

    private DeviceCredentialIdentity identity(Long deviceDbId) {
        AgentDeviceDO device = new AgentDeviceDO();
        device.setId(deviceDbId);
        device.setTenantId(TEST_TENANT_ID);
        device.setOwnerUserId(TEST_USER_ID);
        device.setDeviceId("dev-" + deviceDbId);
        return new DeviceCredentialIdentity(TEST_TENANT_ID, TEST_USER_ID, device);
    }

    private AgentProjectRegisterReqVO reqVO(String projectName) {
        AgentProjectRegisterReqVO reqVO = new AgentProjectRegisterReqVO();
        reqVO.setLocalProjectId(TEST_LOCAL_PROJECT_ID);
        reqVO.setProjectName(projectName);
        reqVO.setWorkspacePath(TEST_WORKSPACE_PATH);
        reqVO.setWorkspaceRealPath(TEST_WORKSPACE_REAL_PATH);
        reqVO.setAgentType(AgentType.CODEX);
        return reqVO;
    }
}
