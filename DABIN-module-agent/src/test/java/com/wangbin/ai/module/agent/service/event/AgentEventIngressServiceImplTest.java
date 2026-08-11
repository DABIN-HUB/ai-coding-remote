package com.wangbin.ai.module.agent.service.event;

import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.command.CommandAckStatus;
import com.wangbin.ai.agent.contract.coordination.CommandAckIngressPayload;
import com.wangbin.ai.agent.contract.coordination.AgentEventIngressPayload;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.contract.event.AgentErrorPayload;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentEventExtensionKeys;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.module.agent.dal.dataobject.command.AgentCommandDO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.dataobject.message.AgentMessageDO;
import com.wangbin.ai.module.agent.dal.dataobject.project.AgentProjectDO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;
import com.wangbin.ai.module.agent.dal.mysql.command.AgentCommandMapper;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceMapper;
import com.wangbin.ai.module.agent.dal.mysql.message.AgentMessageMapper;
import com.wangbin.ai.module.agent.dal.mysql.project.AgentProjectMapper;
import com.wangbin.ai.module.agent.dal.mysql.session.AgentSessionMapper;
import com.wangbin.ai.module.agent.enums.AgentCommandDbStatus;
import com.wangbin.ai.module.agent.enums.AgentSessionDbStatus;
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentEventIngressServiceImplTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final Long TEST_DEVICE_DB_ID = 10L;
    private static final Long TEST_PROJECT_DB_ID = 20L;
    private static final Long TEST_SESSION_DB_ID = 100L;
    private static final Long TEST_COMMAND_DB_ID = 200L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_MESSAGE_ID = "msg-1";
    private static final String TEST_CONTENT = "final answer";
    private static final String TEST_NATIVE_ITEM_ID = "item-1";
    private static final String TEST_COMMAND_ID = "cmd-1";
    private static final String TEST_ERROR_MESSAGE_ID = "msg-error-1";
    private static final String TEST_RELAY_NODE_ID = "relay-1";
    private static final String TEST_CONNECTION_ID = "conn-1";

    private final AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
    private final AgentCommandMapper commandMapper = mock(AgentCommandMapper.class);
    private final AgentDeviceMapper deviceMapper = mock(AgentDeviceMapper.class);
    private final AgentProjectMapper projectMapper = mock(AgentProjectMapper.class);
    private final AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
    private final AgentIdFactory idFactory = mock(AgentIdFactory.class);
    private AgentEventIngressServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentEventIngressServiceImpl(sessionMapper, commandMapper, deviceMapper, projectMapper,
                messageMapper,
                new AgentEventReliabilityPolicy(), idFactory);
    }

    @Test
    void transientDeltaIsNotPersistedAndDoesNotAdvanceSequence() {
        service.handleAgentEvent(payload(event(AgentEventType.AGENT_MESSAGE_DELTA, 1,
                new AgentMessagePayload(TEST_MESSAGE_ID, "assistant", "delta", true, Map.of()))));

        verifyNoInteractions(sessionMapper, commandMapper, messageMapper, idFactory);
    }

    @Test
    void finalAgentMessagePersistsOnceAndDuplicateSequenceIsIgnored() {
        AgentSessionDO session = session(0L);
        when(sessionMapper.selectBySessionId(TEST_SESSION_ID)).thenReturn(session);
        when(messageMapper.selectByMessageId(TEST_MESSAGE_ID)).thenReturn(null);
        AgentEvent event = event(AgentEventType.AGENT_MESSAGE, 1,
                new AgentMessagePayload(TEST_MESSAGE_ID, "assistant", TEST_CONTENT, false,
                        Map.of(AgentEventExtensionKeys.NATIVE_ITEM_ID, TEST_NATIVE_ITEM_ID)));

        service.handleAgentEvent(payload(event));
        service.handleAgentEvent(payload(event));

        ArgumentCaptor<AgentMessageDO> messageCaptor = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(messageMapper, times(1)).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageId()).isEqualTo(TEST_MESSAGE_ID);
        assertThat(messageCaptor.getValue().getContent()).isEqualTo(TEST_CONTENT);
        assertThat(messageCaptor.getValue().getEventSeq()).isEqualTo(1L);
        assertThat(messageCaptor.getValue().getNativeItemId()).isEqualTo(TEST_NATIVE_ITEM_ID);
        assertThat(session.getLastEventSeq()).isEqualTo(1L);
        verify(sessionMapper, times(1)).updateById(session);
    }

    @Test
    void reliableFinalAfterSkippedRealtimeDeltasAdvancesRawSequenceWithoutGap() {
        AgentSessionDO session = session(0L);
        when(sessionMapper.selectBySessionId(TEST_SESSION_ID)).thenReturn(session);

        service.handleAgentEvent(payload(event(AgentEventType.SESSION_STARTED, 1,
                new SessionPayload("native-1", AgentSessionStatus.RUNNING, null, Map.of()))));
        service.handleAgentEvent(payload(event(AgentEventType.AGENT_MESSAGE_DELTA, 2,
                new AgentMessagePayload(TEST_MESSAGE_ID, "assistant", "d1", true, Map.of()))));
        service.handleAgentEvent(payload(event(AgentEventType.AGENT_MESSAGE_DELTA, 3,
                new AgentMessagePayload(TEST_MESSAGE_ID, "assistant", "d2", true, Map.of()))));
        service.handleAgentEvent(payload(event(AgentEventType.AGENT_MESSAGE_DELTA, 4,
                new AgentMessagePayload(TEST_MESSAGE_ID, "assistant", "d3", true, Map.of()))));
        service.handleAgentEvent(payload(event(AgentEventType.AGENT_MESSAGE_DELTA, 5,
                new AgentMessagePayload(TEST_MESSAGE_ID, "assistant", "d4", true, Map.of()))));
        service.handleAgentEvent(payload(event(AgentEventType.AGENT_MESSAGE, 6,
                new AgentMessagePayload(TEST_MESSAGE_ID, "assistant", TEST_CONTENT, false, Map.of()))));

        assertThat(session.getSessionStatus()).isEqualTo(AgentSessionDbStatus.RUNNING.name());
        assertThat(session.getLastEventSeq()).isEqualTo(6L);
        verify(messageMapper, times(1)).insert(any(AgentMessageDO.class));
        verify(sessionMapper, times(2)).updateById(session);
    }

    @Test
    void platformCommandCorrelationPersistsAssistantMessageAndCompletesCommandOnIdle() {
        AgentSessionDO session = session(0L);
        AgentCommandDO command = command(AgentCommandDbStatus.ACKED);
        when(sessionMapper.selectBySessionId(TEST_SESSION_ID)).thenReturn(session);
        when(commandMapper.selectByCommandId(TEST_COMMAND_ID)).thenReturn(command);
        when(messageMapper.selectByMessageId(TEST_MESSAGE_ID)).thenReturn(null);

        service.handleAgentEvent(payload(event(AgentEventType.AGENT_MESSAGE, 1,
                new AgentMessagePayload(TEST_MESSAGE_ID, "assistant", TEST_CONTENT, false,
                        Map.of(AgentEventExtensionKeys.NATIVE_ITEM_ID, TEST_NATIVE_ITEM_ID)),
                TEST_COMMAND_ID)));
        service.handleAgentEvent(payload(event(AgentEventType.SESSION_IDLE, 2,
                new SessionPayload("native-1", AgentSessionStatus.IDLE, null, Map.of()), TEST_COMMAND_ID)));

        ArgumentCaptor<AgentMessageDO> messageCaptor = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getCommandId()).isEqualTo(TEST_COMMAND_DB_ID);
        assertThat(messageCaptor.getValue().getNativeItemId()).isEqualTo(TEST_NATIVE_ITEM_ID);
        assertThat(command.getCommandStatus()).isEqualTo(AgentCommandDbStatus.SUCCEEDED.name());
        assertThat(command.getCompletedTime()).isNotNull();
        assertThat(session.getSessionStatus()).isEqualTo(AgentSessionDbStatus.IDLE.name());
        assertThat(session.getLastEventSeq()).isEqualTo(2L);
    }

    @Test
    void lateAcceptedAckDoesNotDowngradeRunningCommand() {
        AgentSessionDO session = session(0L);
        AgentCommandDO command = command(AgentCommandDbStatus.RUNNING);
        when(commandMapper.selectByCommandId(TEST_COMMAND_ID)).thenReturn(command);
        when(sessionMapper.selectById(TEST_SESSION_DB_ID)).thenReturn(session);

        service.handleCommandAck(ackPayload(new CommandAck(TEST_COMMAND_ID, TEST_SESSION_ID, TEST_DEVICE_ID,
                CommandAckStatus.ACCEPTED, "ACCEPTED", "late accepted", null, Map.of())));

        assertThat(command.getCommandStatus()).isEqualTo(AgentCommandDbStatus.RUNNING.name());
        assertThat(command.getAckedTime()).isNotNull();
        verify(commandMapper).updateById(command);
    }

    @Test
    void retryableErrorPersistsSanitizedSystemMessageWithoutTerminalFailure() {
        AgentSessionDO session = session(0L);
        AgentCommandDO command = command(AgentCommandDbStatus.RUNNING);
        when(sessionMapper.selectBySessionId(TEST_SESSION_ID)).thenReturn(session);
        when(commandMapper.selectByCommandId(TEST_COMMAND_ID)).thenReturn(command);
        when(idFactory.messageId()).thenReturn(TEST_ERROR_MESSAGE_ID);

        service.handleAgentEvent(payload(event(AgentEventType.ERROR, 1,
                new AgentErrorPayload("responseStreamDisconnected", "temporary disconnect", true, Map.of()),
                TEST_COMMAND_ID)));

        ArgumentCaptor<AgentMessageDO> messageCaptor = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageId()).isEqualTo(TEST_ERROR_MESSAGE_ID);
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("temporary disconnect");
        assertThat(command.getCommandStatus()).isEqualTo(AgentCommandDbStatus.RUNNING.name());
        assertThat(session.getSessionStatus()).isEqualTo(AgentSessionDbStatus.RUNNING.name());
        assertThat(session.getLastEventSeq()).isEqualTo(1L);
    }

    private AgentSessionDO session(Long lastEventSeq) {
        AgentSessionDO session = new AgentSessionDO();
        session.setId(TEST_SESSION_DB_ID);
        session.setTenantId(TEST_TENANT_ID);
        session.setSessionId(TEST_SESSION_ID);
        session.setDeviceId(TEST_DEVICE_DB_ID);
        session.setProjectId(TEST_PROJECT_DB_ID);
        session.setOwnerUserId(TEST_USER_ID);
        session.setSessionStatus(AgentSessionDbStatus.RUNNING.name());
        session.setLastEventSeq(lastEventSeq);
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
        return session;
    }

    private AgentCommandDO command(AgentCommandDbStatus status) {
        AgentCommandDO command = new AgentCommandDO();
        command.setId(TEST_COMMAND_DB_ID);
        command.setTenantId(TEST_TENANT_ID);
        command.setCommandId(TEST_COMMAND_ID);
        command.setSessionId(TEST_SESSION_DB_ID);
        command.setDeviceId(TEST_DEVICE_DB_ID);
        command.setProjectId(TEST_PROJECT_DB_ID);
        command.setOwnerUserId(TEST_USER_ID);
        command.setCommandStatus(status.name());
        return command;
    }

    private AgentEvent event(AgentEventType type, long seq,
                             com.wangbin.ai.agent.contract.event.AgentEventPayload payload) {
        return event(type, seq, payload, null);
    }

    private AgentEvent event(AgentEventType type, long seq,
                             com.wangbin.ai.agent.contract.event.AgentEventPayload payload,
                             String platformCommandId) {
        Map<String, Object> extensions = platformCommandId == null
                ? Map.of(AgentEventExtensionKeys.NATIVE_ITEM_ID, TEST_NATIVE_ITEM_ID)
                : Map.of(AgentEventExtensionKeys.NATIVE_ITEM_ID, TEST_NATIVE_ITEM_ID,
                AgentEventExtensionKeys.PLATFORM_COMMAND_ID, platformCommandId);
        return new AgentEvent(null, "trace-1", TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID, TEST_PROJECT_ID,
                TEST_SESSION_ID, seq, AgentType.CODEX, type, priority(type), null, payload,
                extensions);
    }

    private EventPriority priority(AgentEventType type) {
        return type == AgentEventType.AGENT_MESSAGE_DELTA ? EventPriority.TRANSIENT : EventPriority.IMPORTANT;
    }

    private AgentEventIngressPayload payload(AgentEvent event) {
        return new AgentEventIngressPayload(TEST_RELAY_NODE_ID, TEST_CONNECTION_ID, TEST_TENANT_ID,
                TEST_USER_ID, TEST_DEVICE_ID, event, null);
    }

    private CommandAckIngressPayload ackPayload(CommandAck ack) {
        return new CommandAckIngressPayload(TEST_RELAY_NODE_ID, TEST_CONNECTION_ID, TEST_TENANT_ID,
                TEST_USER_ID, TEST_DEVICE_ID, ack, null);
    }
}
