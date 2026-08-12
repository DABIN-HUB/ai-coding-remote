package com.wangbin.ai.module.agent.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.command.CancelCommandPayload;
import com.wangbin.ai.agent.contract.command.PromptCommandPayload;
import com.wangbin.ai.agent.contract.coordination.DeviceRoutePayload;
import com.wangbin.ai.agent.contract.coordination.RelayCommandDispatchPayload;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.contract.enums.SessionControlAction;
import com.wangbin.ai.agent.contract.runtime.AgentRuntimeTypes;
import com.wangbin.ai.framework.common.exception.ServiceException;
import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.module.agent.controller.admin.session.vo.AgentCommandRespVO;
import com.wangbin.ai.module.agent.controller.admin.session.vo.AgentSessionCancelReqVO;
import com.wangbin.ai.module.agent.controller.admin.session.vo.AgentSessionCreateReqVO;
import com.wangbin.ai.module.agent.controller.admin.session.vo.AgentSessionControlRespVO;
import com.wangbin.ai.module.agent.controller.admin.session.vo.AgentSessionRespVO;
import com.wangbin.ai.module.agent.controller.admin.session.vo.AgentSessionSendPromptReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.command.AgentCommandDO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.dataobject.message.AgentMessageDO;
import com.wangbin.ai.module.agent.dal.dataobject.project.AgentProjectDO;
import com.wangbin.ai.module.agent.dal.dataobject.runtime.AgentRuntimeDO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;
import com.wangbin.ai.module.agent.dal.mysql.command.AgentCommandMapper;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceMapper;
import com.wangbin.ai.module.agent.dal.mysql.message.AgentMessageMapper;
import com.wangbin.ai.module.agent.dal.mysql.project.AgentProjectMapper;
import com.wangbin.ai.module.agent.dal.mysql.runtime.AgentRuntimeMapper;
import com.wangbin.ai.module.agent.dal.mysql.session.AgentSessionMapper;
import com.wangbin.ai.module.agent.enums.AgentCommandDbStatus;
import com.wangbin.ai.module.agent.enums.AgentSessionDbStatus;
import com.wangbin.ai.module.agent.enums.DeviceStatus;
import com.wangbin.ai.module.agent.enums.ProjectStatus;
import com.wangbin.ai.module.agent.enums.RuntimeStatus;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import com.wangbin.ai.module.agent.service.command.DeviceRouteLookupService;
import com.wangbin.ai.module.agent.service.command.RelayCommandGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSessionServiceImplTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final Long TEST_DEVICE_DB_ID = 10L;
    private static final Long TEST_PROJECT_DB_ID = 20L;
    private static final Long TEST_RUNTIME_DB_ID = 30L;
    private static final Long TEST_SESSION_DB_ID = 40L;
    private static final Long TEST_COMMAND_DB_ID = 50L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_COMMAND_ID = "cmd-1";
    private static final String TEST_CONTROL_COMMAND_ID = "cmd-cancel-1";
    private static final String TEST_MESSAGE_ID = "msg-1";
    private static final String TEST_PROMPT = "inspect project";
    private static final String TEST_CLIENT_REQUEST_ID = "client-1";
    private static final String TEST_RELAY_NODE_ID = "relay-1";
    private static final String TEST_CONNECTION_ID = "conn-1";
    private static final String WORKSPACE_PATH_EXTENSION = "workspacePath";

    private final AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
    private final AgentProjectMapper projectMapper = mock(AgentProjectMapper.class);
    private final AgentDeviceMapper deviceMapper = mock(AgentDeviceMapper.class);
    private final AgentRuntimeMapper runtimeMapper = mock(AgentRuntimeMapper.class);
    private final AgentCommandMapper commandMapper = mock(AgentCommandMapper.class);
    private final AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
    private final DeviceRouteLookupService routeLookupService = mock(DeviceRouteLookupService.class);
    private final RecordingRelayCommandGateway relayCommandGateway = new RecordingRelayCommandGateway();
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock idempotencyLock = mock(RLock.class);
    private final AgentIdFactory idFactory = mock(AgentIdFactory.class);
    private AgentSessionServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TEST_TENANT_ID);
        service = new AgentSessionServiceImpl(sessionMapper, projectMapper, deviceMapper, runtimeMapper, commandMapper,
                messageMapper, routeLookupService, relayCommandGateway, redissonClient,
                new AgentControlPlaneProperties(), idFactory, new ObjectMapper(), transactionTemplate());
        try {
            when(idempotencyLock.tryLock(anyLong(), any())).thenReturn(true);
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
        when(idempotencyLock.isHeldByCurrentThread()).thenReturn(true);
        when(redissonClient.getLock(anyString())).thenReturn(idempotencyLock);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void createSessionRequiresAvailableRuntimeAndStoresRuntimeId() {
        stubProjectDeviceRoute();
        when(runtimeMapper.selectByDeviceAndType(TEST_DEVICE_DB_ID, AgentRuntimeTypes.CODEX_APP_SERVER))
                .thenReturn(runtime(RuntimeStatus.AVAILABLE));
        when(idFactory.sessionId()).thenReturn(TEST_SESSION_ID);
        ArgumentCaptor<AgentSessionDO> sessionCaptor = ArgumentCaptor.forClass(AgentSessionDO.class);

        AgentSessionRespVO response = service.createSession(createReqVO(), TEST_USER_ID);

        verify(sessionMapper).insert(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getRuntimeId()).isEqualTo(TEST_RUNTIME_DB_ID);
        assertThat(response.getSessionId()).isEqualTo(TEST_SESSION_ID);
        assertThat(response.getRuntimeId()).isEqualTo(TEST_RUNTIME_DB_ID);
    }

    @Test
    void createSessionRejectsWhenRuntimeUnavailable() {
        stubProjectDeviceRoute();
        when(runtimeMapper.selectByDeviceAndType(TEST_DEVICE_DB_ID, AgentRuntimeTypes.CODEX_APP_SERVER))
                .thenReturn(null);

        assertThatThrownBy(() -> service.createSession(createReqVO(), TEST_USER_ID))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void createSessionRejectsOfflineDeviceEvenWhenRuntimeAvailable() {
        when(projectMapper.selectById(TEST_PROJECT_DB_ID)).thenReturn(project());
        when(deviceMapper.selectById(TEST_DEVICE_DB_ID)).thenReturn(device());
        when(runtimeMapper.selectByDeviceAndType(TEST_DEVICE_DB_ID, AgentRuntimeTypes.CODEX_APP_SERVER))
                .thenReturn(runtime(RuntimeStatus.AVAILABLE));
        when(routeLookupService.getRoute(TEST_DEVICE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.createSession(createReqVO(), TEST_USER_ID))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void sendPromptCreatesUserMessageAndDispatchesCommandWithoutWorkspacePath() {
        AgentSessionDO session = session();
        AgentProjectDO project = project();
        AgentDeviceDO device = device();
        when(sessionMapper.selectBySessionId(TEST_SESSION_ID)).thenReturn(session);
        when(projectMapper.selectById(TEST_PROJECT_DB_ID)).thenReturn(project);
        when(deviceMapper.selectById(TEST_DEVICE_DB_ID)).thenReturn(device);
        when(routeLookupService.getRoute(TEST_DEVICE_ID)).thenReturn(route());
        when(idFactory.commandId()).thenReturn(TEST_COMMAND_ID);
        when(idFactory.messageId()).thenReturn(TEST_MESSAGE_ID);
        AtomicReference<AgentCommandDO> commandRef = new AtomicReference<>();
        doAnswer(invocation -> {
            AgentCommandDO command = invocation.getArgument(0);
            command.setId(TEST_COMMAND_DB_ID);
            commandRef.set(command);
            return 1;
        }).when(commandMapper).insert(any(AgentCommandDO.class));
        when(commandMapper.selectByCommandId(TEST_COMMAND_ID)).thenAnswer(invocation -> commandRef.get());

        AgentCommandRespVO response = service.sendPrompt(sendPromptReqVO(), TEST_USER_ID);

        assertThat(response.getCommandId()).isEqualTo(TEST_COMMAND_ID);
        assertThat(response.getCommandStatus()).isEqualTo(AgentCommandDbStatus.ROUTING.name());
        ArgumentCaptor<AgentMessageDO> messageCaptor = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageId()).isEqualTo(TEST_MESSAGE_ID);
        assertThat(messageCaptor.getValue().getCommandId()).isEqualTo(TEST_COMMAND_DB_ID);
        assertThat(messageCaptor.getValue().getContent()).isEqualTo(TEST_PROMPT);
        RelayCommandDispatchPayload dispatch = relayCommandGateway.payload;
        assertThat(dispatch.targetRelayNodeId()).isEqualTo(TEST_RELAY_NODE_ID);
        assertThat(dispatch.command().commandId()).isEqualTo(TEST_COMMAND_ID);
        assertThat(dispatch.command().extensions()).doesNotContainKey(WORKSPACE_PATH_EXTENSION);
        PromptCommandPayload promptPayload = (PromptCommandPayload) dispatch.command().payload();
        assertThat(promptPayload.prompt()).isEqualTo(TEST_PROMPT);
        assertThat(promptPayload.extensions()).doesNotContainKey(WORKSPACE_PATH_EXTENSION);
    }

    @Test
    void markAckTimeoutOnlyTransitionsCreatedOrRoutingCommands() {
        AgentCommandDO command = new AgentCommandDO();
        command.setCommandId(TEST_COMMAND_ID);
        command.setCommandStatus(AgentCommandDbStatus.ROUTING.name());
        when(commandMapper.selectByCommandId(TEST_COMMAND_ID)).thenReturn(command);
        LocalDateTime timeoutAt = LocalDateTime.now();

        service.markAckTimeout(TEST_COMMAND_ID, timeoutAt);

        assertThat(command.getCommandStatus()).isEqualTo(AgentCommandDbStatus.TIMEOUT.name());
        assertThat(command.getCompletedTime()).isEqualTo(timeoutAt);
        verify(commandMapper).updateById(command);
    }

    @Test
    void cancelSessionCommandCreatesControlCommandAndDispatchesWithoutChangingPromptTerminalState() {
        AgentSessionDO session = runningSession();
        AgentCommandDO targetCommand = promptCommand(AgentCommandDbStatus.RUNNING);
        when(sessionMapper.selectBySessionId(TEST_SESSION_ID)).thenReturn(session);
        when(projectMapper.selectById(TEST_PROJECT_DB_ID)).thenReturn(project());
        when(deviceMapper.selectById(TEST_DEVICE_DB_ID)).thenReturn(device());
        when(commandMapper.selectByCommandId(TEST_COMMAND_ID)).thenReturn(targetCommand);
        when(routeLookupService.getRoute(TEST_DEVICE_ID)).thenReturn(route());
        when(idFactory.commandId()).thenReturn(TEST_CONTROL_COMMAND_ID);
        AtomicReference<AgentCommandDO> controlRef = new AtomicReference<>();
        doAnswer(invocation -> {
            AgentCommandDO command = invocation.getArgument(0);
            command.setId(TEST_COMMAND_DB_ID + 1);
            controlRef.set(command);
            return 1;
        }).when(commandMapper).insert(any(AgentCommandDO.class));
        when(commandMapper.selectByCommandId(TEST_CONTROL_COMMAND_ID)).thenAnswer(invocation -> controlRef.get());

        AgentSessionControlRespVO response = service.cancelSessionCommand(cancelReqVO(null), TEST_USER_ID);

        assertThat(response.getControlCommandId()).isEqualTo(TEST_CONTROL_COMMAND_ID);
        assertThat(response.getAction()).isEqualTo(SessionControlAction.CANCEL);
        assertThat(response.getTargetCommandId()).isEqualTo(TEST_COMMAND_ID);
        assertThat(response.getCommandStatus()).isEqualTo(AgentCommandDbStatus.ROUTING.name());
        assertThat(targetCommand.getCommandStatus()).isEqualTo(AgentCommandDbStatus.RUNNING.name());
        assertThat(relayCommandGateway.payload.command().commandType()).isEqualTo(CommandType.CANCEL);
        CancelCommandPayload payload = (CancelCommandPayload) relayCommandGateway.payload.command().payload();
        assertThat(payload.targetCommandId()).isEqualTo(TEST_COMMAND_ID);
    }

    @Test
    void cancelSessionCommandWithClientRequestIdReturnsExistingControlCommand() {
        AgentSessionDO session = runningSession();
        AgentCommandDO existing = controlCommand(AgentCommandDbStatus.ROUTING);
        when(sessionMapper.selectBySessionId(TEST_SESSION_ID)).thenReturn(session);
        when(commandMapper.selectByClientRequestId(TEST_SESSION_DB_ID, TEST_USER_ID, CommandType.CANCEL.name(),
                TEST_CLIENT_REQUEST_ID)).thenReturn(existing);

        AgentSessionControlRespVO response = service.cancelSessionCommand(cancelReqVO(TEST_CLIENT_REQUEST_ID),
                TEST_USER_ID);

        assertThat(response.getControlCommandId()).isEqualTo(TEST_CONTROL_COMMAND_ID);
        assertThat(response.getCommandStatus()).isEqualTo(AgentCommandDbStatus.ROUTING.name());
        assertThat(relayCommandGateway.payload).isNull();
    }

    @Test
    void cancelSessionCommandRejectsInactiveTargetCommand() {
        AgentSessionDO session = runningSession();
        when(sessionMapper.selectBySessionId(TEST_SESSION_ID)).thenReturn(session);
        when(projectMapper.selectById(TEST_PROJECT_DB_ID)).thenReturn(project());
        when(deviceMapper.selectById(TEST_DEVICE_DB_ID)).thenReturn(device());
        when(commandMapper.selectByCommandId(TEST_COMMAND_ID)).thenReturn(promptCommand(AgentCommandDbStatus.SUCCEEDED));

        assertThatThrownBy(() -> service.cancelSessionCommand(cancelReqVO(null), TEST_USER_ID))
                .isInstanceOf(ServiceException.class);
    }

    private void stubProjectDeviceRoute() {
        when(projectMapper.selectById(TEST_PROJECT_DB_ID)).thenReturn(project());
        when(deviceMapper.selectById(TEST_DEVICE_DB_ID)).thenReturn(device());
        when(routeLookupService.getRoute(TEST_DEVICE_ID)).thenReturn(route());
    }

    private AgentSessionCreateReqVO createReqVO() {
        AgentSessionCreateReqVO reqVO = new AgentSessionCreateReqVO();
        reqVO.setProjectId(TEST_PROJECT_DB_ID);
        reqVO.setAgentType(AgentType.CODEX);
        return reqVO;
    }

    private AgentSessionSendPromptReqVO sendPromptReqVO() {
        AgentSessionSendPromptReqVO reqVO = new AgentSessionSendPromptReqVO();
        reqVO.setSessionId(TEST_SESSION_ID);
        reqVO.setContent(TEST_PROMPT);
        return reqVO;
    }

    private AgentSessionCancelReqVO cancelReqVO(String clientRequestId) {
        AgentSessionCancelReqVO reqVO = new AgentSessionCancelReqVO();
        reqVO.setSessionId(TEST_SESSION_ID);
        reqVO.setTargetCommandId(TEST_COMMAND_ID);
        reqVO.setClientRequestId(clientRequestId);
        reqVO.setReason("cancel");
        return reqVO;
    }

    private AgentSessionDO session() {
        AgentSessionDO session = new AgentSessionDO();
        session.setId(TEST_SESSION_DB_ID);
        session.setTenantId(TEST_TENANT_ID);
        session.setSessionId(TEST_SESSION_ID);
        session.setDeviceId(TEST_DEVICE_DB_ID);
        session.setProjectId(TEST_PROJECT_DB_ID);
        session.setRuntimeId(TEST_RUNTIME_DB_ID);
        session.setOwnerUserId(TEST_USER_ID);
        session.setAgentType(AgentType.CODEX.name());
        session.setSessionStatus(AgentSessionDbStatus.IDLE.name());
        session.setLastEventSeq(0L);
        return session;
    }

    private AgentSessionDO runningSession() {
        AgentSessionDO session = session();
        session.setSessionStatus(AgentSessionDbStatus.RUNNING.name());
        return session;
    }

    private AgentCommandDO promptCommand(AgentCommandDbStatus status) {
        AgentCommandDO command = new AgentCommandDO();
        command.setId(TEST_COMMAND_DB_ID);
        command.setTenantId(TEST_TENANT_ID);
        command.setCommandId(TEST_COMMAND_ID);
        command.setSessionId(TEST_SESSION_DB_ID);
        command.setDeviceId(TEST_DEVICE_DB_ID);
        command.setProjectId(TEST_PROJECT_DB_ID);
        command.setOwnerUserId(TEST_USER_ID);
        command.setCommandType(CommandType.PROMPT.name());
        command.setCommandStatus(status.name());
        return command;
    }

    private AgentCommandDO controlCommand(AgentCommandDbStatus status) {
        AgentCommandDO command = new AgentCommandDO();
        command.setId(TEST_COMMAND_DB_ID + 1);
        command.setTenantId(TEST_TENANT_ID);
        command.setCommandId(TEST_CONTROL_COMMAND_ID);
        command.setSessionId(TEST_SESSION_DB_ID);
        command.setDeviceId(TEST_DEVICE_DB_ID);
        command.setProjectId(TEST_PROJECT_DB_ID);
        command.setOwnerUserId(TEST_USER_ID);
        command.setCommandType(CommandType.CANCEL.name());
        command.setCommandStatus(status.name());
        command.setRequestId(TEST_CLIENT_REQUEST_ID);
        return command;
    }

    private AgentProjectDO project() {
        AgentProjectDO project = new AgentProjectDO();
        project.setId(TEST_PROJECT_DB_ID);
        project.setTenantId(TEST_TENANT_ID);
        project.setDeviceId(TEST_DEVICE_DB_ID);
        project.setProjectId(TEST_PROJECT_ID);
        project.setOwnerUserId(TEST_USER_ID);
        project.setAgentType(AgentType.CODEX.name());
        project.setProjectStatus(ProjectStatus.ACTIVE.name());
        return project;
    }

    private AgentDeviceDO device() {
        AgentDeviceDO device = new AgentDeviceDO();
        device.setId(TEST_DEVICE_DB_ID);
        device.setTenantId(TEST_TENANT_ID);
        device.setDeviceId(TEST_DEVICE_ID);
        device.setOwnerUserId(TEST_USER_ID);
        device.setDeviceStatus(DeviceStatus.ACTIVE.name());
        return device;
    }

    private AgentRuntimeDO runtime(RuntimeStatus status) {
        AgentRuntimeDO runtime = new AgentRuntimeDO();
        runtime.setId(TEST_RUNTIME_DB_ID);
        runtime.setTenantId(TEST_TENANT_ID);
        runtime.setDeviceId(TEST_DEVICE_DB_ID);
        runtime.setRuntimeType(AgentRuntimeTypes.CODEX_APP_SERVER);
        runtime.setRuntimeStatus(status.name());
        return runtime;
    }

    private DeviceRoutePayload route() {
        return new DeviceRoutePayload(TEST_RELAY_NODE_ID, TEST_CONNECTION_ID, TEST_TENANT_ID, TEST_USER_ID,
                TEST_DEVICE_ID, Instant.now(), Instant.now());
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        });
    }

    private static final class RecordingRelayCommandGateway implements RelayCommandGateway {

        private RelayCommandDispatchPayload payload;

        @Override
        public void dispatch(RelayCommandDispatchPayload payload) {
            this.payload = payload;
        }
    }
}
