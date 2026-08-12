package com.wangbin.ai.module.agent.service.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.command.CommandAckStatus;
import com.wangbin.ai.agent.contract.command.PermissionDecisionCommandPayload;
import com.wangbin.ai.agent.contract.coordination.DeviceRoutePayload;
import com.wangbin.ai.agent.contract.coordination.RelayCommandDispatchPayload;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.enums.PermissionResolutionStatus;
import com.wangbin.ai.agent.contract.enums.PermissionType;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.PermissionRequiredPayload;
import com.wangbin.ai.agent.contract.event.PermissionResolvedPayload;
import com.wangbin.ai.agent.contract.permission.CommandExecutionPermissionDetail;
import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.module.agent.controller.admin.permission.vo.AgentPermissionDecideReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.command.AgentCommandDO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.dataobject.permission.AgentPermissionRequestDO;
import com.wangbin.ai.module.agent.dal.dataobject.project.AgentProjectDO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;
import com.wangbin.ai.module.agent.dal.mysql.command.AgentCommandMapper;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceMapper;
import com.wangbin.ai.module.agent.dal.mysql.permission.AgentPermissionRequestMapper;
import com.wangbin.ai.module.agent.dal.mysql.project.AgentProjectMapper;
import com.wangbin.ai.module.agent.dal.mysql.session.AgentSessionMapper;
import com.wangbin.ai.module.agent.enums.AgentCommandDbStatus;
import com.wangbin.ai.module.agent.enums.AgentPermissionStatus;
import com.wangbin.ai.module.agent.enums.AgentSessionDbStatus;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentPermissionServiceImplTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final Long TEST_DEVICE_DB_ID = 10L;
    private static final Long TEST_PROJECT_DB_ID = 20L;
    private static final Long TEST_SESSION_DB_ID = 30L;
    private static final Long TEST_PROMPT_COMMAND_DB_ID = 40L;
    private static final Long TEST_DECISION_COMMAND_DB_ID = 50L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_PERMISSION_ID = "perm_1";
    private static final String TEST_PROMPT_COMMAND_ID = "cmd_prompt";
    private static final String TEST_DECISION_COMMAND_ID = "cmd_decision";
    private static final String TEST_RELAY_NODE_ID = "relay-1";
    private static final String TEST_CONNECTION_ID = "conn-1";

    private final AgentPermissionRequestMapper permissionMapper = mock(AgentPermissionRequestMapper.class);
    private final AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
    private final AgentCommandMapper commandMapper = mock(AgentCommandMapper.class);
    private final AgentDeviceMapper deviceMapper = mock(AgentDeviceMapper.class);
    private final AgentProjectMapper projectMapper = mock(AgentProjectMapper.class);
    private final DeviceRouteLookupService routeLookupService = mock(DeviceRouteLookupService.class);
    private final RecordingRelayCommandGateway relayCommandGateway = new RecordingRelayCommandGateway();
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final AgentIdFactory idFactory = mock(AgentIdFactory.class);
    private AgentPermissionServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        TenantContextHolder.setTenantId(TEST_TENANT_ID);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        service = new AgentPermissionServiceImpl(permissionMapper, sessionMapper, commandMapper, deviceMapper,
                projectMapper, routeLookupService, relayCommandGateway, redissonClient,
                new AgentControlPlaneProperties(), idFactory, new ObjectMapper(), transactionTemplate());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void permissionRequiredInsertsPendingSanitizedRequest() {
        AgentSessionDO session = session(AgentSessionDbStatus.RUNNING);
        AgentCommandDO promptCommand = command(TEST_PROMPT_COMMAND_DB_ID, TEST_PROMPT_COMMAND_ID,
                AgentCommandDbStatus.RUNNING);
        when(commandMapper.selectByCommandId(TEST_PROMPT_COMMAND_ID)).thenReturn(promptCommand);

        service.handlePermissionRequired(session, mock(AgentEvent.class), TEST_PROMPT_COMMAND_ID, requiredPayload());

        ArgumentCaptor<AgentPermissionRequestDO> captor = ArgumentCaptor.forClass(AgentPermissionRequestDO.class);
        verify(permissionMapper).insert(captor.capture());
        assertThat(captor.getValue().getPermissionId()).isEqualTo(TEST_PERMISSION_ID);
        assertThat(captor.getValue().getPermissionStatus()).isEqualTo(AgentPermissionStatus.PENDING.name());
        assertThat(captor.getValue().getCommandId()).isEqualTo(TEST_PROMPT_COMMAND_DB_ID);
        assertThat(captor.getValue().getRequestJson()).contains("git status").doesNotContain("requestId");
    }

    @Test
    void ownerDecisionCreatesPermissionCommandAndDispatches() {
        AgentPermissionRequestDO permission = permission(AgentPermissionStatus.PENDING);
        when(permissionMapper.selectByPermissionId(TEST_PERMISSION_ID)).thenReturn(permission);
        stubSessionDeviceProjectRoute();
        when(idFactory.commandId()).thenReturn(TEST_DECISION_COMMAND_ID);
        doAnswer(invocation -> {
            AgentCommandDO command = invocation.getArgument(0);
            command.setId(TEST_DECISION_COMMAND_DB_ID);
            return 1;
        }).when(commandMapper).insert(any(AgentCommandDO.class));

        service.decidePermission(decideReq(PermissionDecision.APPROVED_FOR_SESSION), TEST_USER_ID);

        assertThat(permission.getPermissionStatus()).isEqualTo(AgentPermissionStatus.DECISION_ROUTING.name());
        assertThat(permission.getDecisionCommandId()).isEqualTo(TEST_DECISION_COMMAND_DB_ID);
        assertThat(relayCommandGateway.payload.command().commandType()).isEqualTo(CommandType.APPROVE_PERMISSION);
        PermissionDecisionCommandPayload payload =
                (PermissionDecisionCommandPayload) relayCommandGateway.payload.command().payload();
        assertThat(payload.permissionId()).isEqualTo(TEST_PERMISSION_ID);
        assertThat(payload.decision()).isEqualTo(PermissionDecision.APPROVED_FOR_SESSION);
    }

    @Test
    void routeMissingMarksDecisionCommandAndPermissionFailed() {
        AgentPermissionRequestDO permission = permission(AgentPermissionStatus.PENDING);
        when(permissionMapper.selectByPermissionId(TEST_PERMISSION_ID)).thenReturn(permission);
        stubSessionDeviceProject();
        when(routeLookupService.getRoute(TEST_DEVICE_ID)).thenReturn(null);
        when(idFactory.commandId()).thenReturn(TEST_DECISION_COMMAND_ID);
        doAnswer(invocation -> {
            AgentCommandDO command = invocation.getArgument(0);
            command.setId(TEST_DECISION_COMMAND_DB_ID);
            return 1;
        }).when(commandMapper).insert(any(AgentCommandDO.class));

        service.decidePermission(decideReq(PermissionDecision.REJECTED), TEST_USER_ID);

        assertThat(permission.getPermissionStatus()).isEqualTo(AgentPermissionStatus.FAILED.name());
        assertThat(relayCommandGateway.payload).isNull();
    }

    @Test
    void dispatchFailureMarksDecisionCommandAndPermissionFailed() {
        AgentPermissionRequestDO permission = permission(AgentPermissionStatus.PENDING);
        when(permissionMapper.selectByPermissionId(TEST_PERMISSION_ID)).thenReturn(permission);
        stubSessionDeviceProjectRoute();
        when(idFactory.commandId()).thenReturn(TEST_DECISION_COMMAND_ID);
        doAnswer(invocation -> {
            AgentCommandDO command = invocation.getArgument(0);
            command.setId(TEST_DECISION_COMMAND_DB_ID);
            return 1;
        }).when(commandMapper).insert(any(AgentCommandDO.class));
        relayCommandGateway.failDispatch = true;

        service.decidePermission(decideReq(PermissionDecision.REJECTED), TEST_USER_ID);

        assertThat(permission.getPermissionStatus()).isEqualTo(AgentPermissionStatus.FAILED.name());
        ArgumentCaptor<AgentCommandDO> captor = ArgumentCaptor.forClass(AgentCommandDO.class);
        verify(commandMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(command -> {
            assertThat(command.getId()).isEqualTo(TEST_DECISION_COMMAND_DB_ID);
            assertThat(command.getCommandStatus()).isEqualTo(AgentCommandDbStatus.FAILED.name());
        });
    }

    @Test
    void resolvedApprovesPermissionAndCompletesDecisionCommandWithoutCompletingPromptCommand() {
        AgentPermissionRequestDO permission = permission(AgentPermissionStatus.DECISION_SENT);
        permission.setDecisionCommandId(TEST_DECISION_COMMAND_DB_ID);
        AgentCommandDO decisionCommand = command(TEST_DECISION_COMMAND_DB_ID, TEST_DECISION_COMMAND_ID,
                AgentCommandDbStatus.ACKED);
        AgentCommandDO promptCommand = command(TEST_PROMPT_COMMAND_DB_ID, TEST_PROMPT_COMMAND_ID,
                AgentCommandDbStatus.RUNNING);
        AgentSessionDO session = session(AgentSessionDbStatus.WAITING_PERMISSION);
        when(permissionMapper.selectByPermissionId(TEST_PERMISSION_ID)).thenReturn(permission);
        when(commandMapper.selectByCommandId(TEST_DECISION_COMMAND_ID)).thenReturn(decisionCommand);
        when(commandMapper.selectById(TEST_DECISION_COMMAND_DB_ID)).thenReturn(decisionCommand);
        when(commandMapper.selectById(TEST_PROMPT_COMMAND_DB_ID)).thenReturn(promptCommand);
        when(sessionMapper.selectById(TEST_SESSION_DB_ID)).thenReturn(session);

        service.handlePermissionResolved(new PermissionResolvedPayload(TEST_PERMISSION_ID,
                PermissionType.COMMAND_EXECUTION, PermissionDecision.APPROVED_FOR_SESSION,
                PermissionResolutionStatus.APPROVED, TEST_DECISION_COMMAND_ID, null, null, Map.of()));

        assertThat(permission.getPermissionStatus()).isEqualTo(AgentPermissionStatus.APPROVED.name());
        assertThat(decisionCommand.getCommandStatus()).isEqualTo(AgentCommandDbStatus.SUCCEEDED.name());
        assertThat(promptCommand.getCommandStatus()).isEqualTo(AgentCommandDbStatus.RUNNING.name());
        assertThat(session.getSessionStatus()).isEqualTo(AgentSessionDbStatus.RUNNING.name());
    }

    @Test
    void decisionCommandAckMovesPermissionToDecisionSentOrFailed() {
        AgentPermissionRequestDO permission = permission(AgentPermissionStatus.DECISION_ROUTING);
        AgentCommandDO command = command(TEST_DECISION_COMMAND_DB_ID, TEST_DECISION_COMMAND_ID,
                AgentCommandDbStatus.ACKED);
        when(permissionMapper.selectByDecisionCommandId(TEST_DECISION_COMMAND_DB_ID)).thenReturn(permission);

        service.handleDecisionCommandAck(command, new CommandAck(TEST_DECISION_COMMAND_ID, TEST_SESSION_ID,
                TEST_DEVICE_ID, CommandAckStatus.ACCEPTED, "ACCEPTED", "accepted", null, Map.of()));

        assertThat(permission.getPermissionStatus()).isEqualTo(AgentPermissionStatus.DECISION_SENT.name());

        permission.setPermissionStatus(AgentPermissionStatus.DECISION_ROUTING.name());
        service.handleDecisionCommandAck(command, new CommandAck(TEST_DECISION_COMMAND_ID, TEST_SESSION_ID,
                TEST_DEVICE_ID, CommandAckStatus.FAILED, "FAILED", "failed", null, Map.of()));

        assertThat(permission.getPermissionStatus()).isEqualTo(AgentPermissionStatus.FAILED.name());
    }

    private PermissionRequiredPayload requiredPayload() {
        return new PermissionRequiredPayload(TEST_PERMISSION_ID, PermissionType.COMMAND_EXECUTION,
                "Command approval required", "reason",
                new CommandExecutionPermissionDetail("item-1", "turn-1", "git status", ".", "reason", null,
                        List.of(PermissionDecision.APPROVED, PermissionDecision.REJECTED), Map.of()),
                Map.of());
    }

    private AgentPermissionDecideReqVO decideReq(PermissionDecision decision) {
        AgentPermissionDecideReqVO reqVO = new AgentPermissionDecideReqVO();
        reqVO.setPermissionId(TEST_PERMISSION_ID);
        reqVO.setDecision(decision);
        reqVO.setReason("ok");
        return reqVO;
    }

    private AgentPermissionRequestDO permission(AgentPermissionStatus status) {
        AgentPermissionRequestDO permission = new AgentPermissionRequestDO();
        permission.setId(1L);
        permission.setTenantId(TEST_TENANT_ID);
        permission.setPermissionId(TEST_PERMISSION_ID);
        permission.setSessionId(TEST_SESSION_DB_ID);
        permission.setCommandId(TEST_PROMPT_COMMAND_DB_ID);
        permission.setDeviceId(TEST_DEVICE_DB_ID);
        permission.setProjectId(TEST_PROJECT_DB_ID);
        permission.setOwnerUserId(TEST_USER_ID);
        permission.setPermissionType(PermissionType.COMMAND_EXECUTION.name());
        permission.setPermissionStatus(status.name());
        return permission;
    }

    private void stubSessionDeviceProjectRoute() {
        stubSessionDeviceProject();
        when(routeLookupService.getRoute(TEST_DEVICE_ID)).thenReturn(new DeviceRoutePayload(TEST_RELAY_NODE_ID,
                TEST_CONNECTION_ID, TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID, Instant.now(), Instant.now()));
    }

    private void stubSessionDeviceProject() {
        when(sessionMapper.selectById(TEST_SESSION_DB_ID)).thenReturn(session(AgentSessionDbStatus.RUNNING));
        AgentDeviceDO device = new AgentDeviceDO();
        device.setId(TEST_DEVICE_DB_ID);
        device.setTenantId(TEST_TENANT_ID);
        device.setDeviceId(TEST_DEVICE_ID);
        when(deviceMapper.selectById(TEST_DEVICE_DB_ID)).thenReturn(device);
        AgentProjectDO project = new AgentProjectDO();
        project.setId(TEST_PROJECT_DB_ID);
        project.setTenantId(TEST_TENANT_ID);
        project.setProjectId(TEST_PROJECT_ID);
        when(projectMapper.selectById(TEST_PROJECT_DB_ID)).thenReturn(project);
    }

    private AgentSessionDO session(AgentSessionDbStatus status) {
        AgentSessionDO session = new AgentSessionDO();
        session.setId(TEST_SESSION_DB_ID);
        session.setTenantId(TEST_TENANT_ID);
        session.setSessionId(TEST_SESSION_ID);
        session.setDeviceId(TEST_DEVICE_DB_ID);
        session.setProjectId(TEST_PROJECT_DB_ID);
        session.setOwnerUserId(TEST_USER_ID);
        session.setAgentType(AgentType.CODEX.name());
        session.setSessionStatus(status.name());
        return session;
    }

    private AgentCommandDO command(Long id, String commandId, AgentCommandDbStatus status) {
        AgentCommandDO command = new AgentCommandDO();
        command.setId(id);
        command.setTenantId(TEST_TENANT_ID);
        command.setCommandId(commandId);
        command.setSessionId(TEST_SESSION_DB_ID);
        command.setDeviceId(TEST_DEVICE_DB_ID);
        command.setProjectId(TEST_PROJECT_DB_ID);
        command.setOwnerUserId(TEST_USER_ID);
        command.setCommandStatus(status.name());
        return command;
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
        private boolean failDispatch;

        @Override
        public void dispatch(RelayCommandDispatchPayload payload) {
            if (failDispatch) {
                throw new IllegalStateException("dispatch failed");
            }
            this.payload = payload;
        }
    }
}
