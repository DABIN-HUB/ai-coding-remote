package com.wangbin.ai.module.agent.service.event;

import com.wangbin.ai.agent.contract.coordination.AgentEventIngressPayload;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.EventPriority;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.AgentMessagePayload;
import com.wangbin.ai.module.agent.dal.dataobject.message.AgentMessageDO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;
import com.wangbin.ai.module.agent.dal.mysql.command.AgentCommandMapper;
import com.wangbin.ai.module.agent.dal.mysql.message.AgentMessageMapper;
import com.wangbin.ai.module.agent.dal.mysql.session.AgentSessionMapper;
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
    private static final Long TEST_SESSION_DB_ID = 100L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_MESSAGE_ID = "msg-1";
    private static final String TEST_CONTENT = "final answer";
    private static final String TEST_NATIVE_ITEM_ID = "item-1";
    private static final String TEST_RELAY_NODE_ID = "relay-1";
    private static final String TEST_CONNECTION_ID = "conn-1";

    private final AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
    private final AgentCommandMapper commandMapper = mock(AgentCommandMapper.class);
    private final AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
    private final AgentIdFactory idFactory = mock(AgentIdFactory.class);
    private AgentEventIngressServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentEventIngressServiceImpl(sessionMapper, commandMapper, messageMapper,
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
                new AgentMessagePayload(TEST_MESSAGE_ID, "assistant", TEST_CONTENT, false, Map.of()));

        service.handleAgentEvent(payload(event));
        service.handleAgentEvent(payload(event));

        ArgumentCaptor<AgentMessageDO> messageCaptor = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(messageMapper, times(1)).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageId()).isEqualTo(TEST_MESSAGE_ID);
        assertThat(messageCaptor.getValue().getContent()).isEqualTo(TEST_CONTENT);
        assertThat(messageCaptor.getValue().getEventSeq()).isEqualTo(1L);
        assertThat(session.getLastEventSeq()).isEqualTo(1L);
        verify(sessionMapper, times(1)).updateById(session);
    }

    @Test
    void sequenceGapMarksSessionWithoutAdvancingLastEventSeq() {
        AgentSessionDO session = session(1L);
        when(sessionMapper.selectBySessionId(TEST_SESSION_ID)).thenReturn(session);

        service.handleAgentEvent(payload(event(AgentEventType.AGENT_MESSAGE, 3,
                new AgentMessagePayload(TEST_MESSAGE_ID, "assistant", TEST_CONTENT, false, Map.of()))));

        assertThat(session.getSessionStatus()).isEqualTo(AgentSessionDbStatus.EVENT_GAP.name());
        assertThat(session.getLastEventSeq()).isEqualTo(1L);
        verify(messageMapper, never()).insert(any(AgentMessageDO.class));
        verify(sessionMapper).updateById(session);
    }

    private AgentSessionDO session(Long lastEventSeq) {
        AgentSessionDO session = new AgentSessionDO();
        session.setId(TEST_SESSION_DB_ID);
        session.setTenantId(TEST_TENANT_ID);
        session.setSessionId(TEST_SESSION_ID);
        session.setOwnerUserId(TEST_USER_ID);
        session.setSessionStatus(AgentSessionDbStatus.RUNNING.name());
        session.setLastEventSeq(lastEventSeq);
        return session;
    }

    private AgentEvent event(AgentEventType type, long seq,
                             com.wangbin.ai.agent.contract.event.AgentEventPayload payload) {
        return new AgentEvent(null, "trace-1", TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID, TEST_PROJECT_ID,
                TEST_SESSION_ID, seq, AgentType.CODEX, type, priority(type), null, payload,
                Map.of("nativeItemId", TEST_NATIVE_ITEM_ID));
    }

    private EventPriority priority(AgentEventType type) {
        return type == AgentEventType.AGENT_MESSAGE_DELTA ? EventPriority.TRANSIENT : EventPriority.IMPORTANT;
    }

    private AgentEventIngressPayload payload(AgentEvent event) {
        return new AgentEventIngressPayload(TEST_RELAY_NODE_ID, TEST_CONNECTION_ID, event, null);
    }
}
