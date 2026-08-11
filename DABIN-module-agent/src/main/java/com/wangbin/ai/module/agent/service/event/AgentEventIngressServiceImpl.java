package com.wangbin.ai.module.agent.service.event;

import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.command.CommandAckStatus;
import com.wangbin.ai.agent.contract.coordination.AgentEventIngressPayload;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.event.*;
import com.wangbin.ai.module.agent.dal.dataobject.command.AgentCommandDO;
import com.wangbin.ai.module.agent.dal.dataobject.message.AgentMessageDO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;
import com.wangbin.ai.module.agent.dal.mysql.command.AgentCommandMapper;
import com.wangbin.ai.module.agent.dal.mysql.message.AgentMessageMapper;
import com.wangbin.ai.module.agent.dal.mysql.session.AgentSessionMapper;
import com.wangbin.ai.module.agent.enums.*;
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.SESSION_NOT_EXISTS;

@Service
@RequiredArgsConstructor
public class AgentEventIngressServiceImpl implements AgentEventIngressService {

    private final AgentSessionMapper sessionMapper;
    private final AgentCommandMapper commandMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentEventReliabilityPolicy reliabilityPolicy;
    private final AgentIdFactory idFactory;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleAgentEvent(AgentEventIngressPayload payload) {
        AgentEvent event = payload.event();
        if (!reliabilityPolicy.shouldPersist(event)) {
            return;
        }
        AgentSessionDO session = sessionMapper.selectBySessionId(event.sessionId());
        if (session == null) {
            throw exception(SESSION_NOT_EXISTS);
        }
        long lastSeq = session.getLastEventSeq() == null ? 0L : session.getLastEventSeq();
        if (event.seq() <= lastSeq) {
            return;
        }
        if (event.seq() > lastSeq + 1) {
            session.setSessionStatus(AgentSessionDbStatus.EVENT_GAP.name());
            session.setErrorMessage("AgentEvent sequence gap: expected " + (lastSeq + 1) + ", actual " + event.seq());
            sessionMapper.updateById(session);
            return;
        }
        applyEvent(session, event);
        session.setLastEventSeq(event.seq());
        session.setLastActiveTime(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleCommandAck(CommandAck ack) {
        AgentCommandDO command = commandMapper.selectByCommandId(ack.commandId());
        if (command == null) {
            return;
        }
        if (AgentCommandDbStatus.SUCCEEDED.name().equals(command.getCommandStatus())
                || AgentCommandDbStatus.FAILED.name().equals(command.getCommandStatus())) {
            return;
        }
        command.setAckCode(ack.code());
        command.setAckMessage(ack.message());
        command.setAckedTime(LocalDateTime.now());
        if (ack.status() == CommandAckStatus.ACCEPTED || ack.status() == CommandAckStatus.DUPLICATE) {
            command.setCommandStatus(AgentCommandDbStatus.ACKED.name());
        } else if (ack.status() == CommandAckStatus.REJECTED || ack.status() == CommandAckStatus.FAILED) {
            command.setCommandStatus(AgentCommandDbStatus.REJECTED.name());
            command.setCompletedTime(LocalDateTime.now());
        }
        commandMapper.updateById(command);
    }

    private void applyEvent(AgentSessionDO session, AgentEvent event) {
        if (event.type() == AgentEventType.SESSION_STARTED) {
            session.setSessionStatus(AgentSessionDbStatus.RUNNING.name());
            session.setStartedTime(LocalDateTime.now());
            if (event.payload() instanceof SessionPayload payload) {
                session.setNativeSessionId(payload.nativeSessionId());
            }
            return;
        }
        if (event.type() == AgentEventType.SESSION_IDLE) {
            session.setSessionStatus(AgentSessionDbStatus.IDLE.name());
            return;
        }
        if (event.type() == AgentEventType.SESSION_COMPLETED) {
            session.setSessionStatus(AgentSessionDbStatus.CLOSED.name());
            session.setClosedTime(LocalDateTime.now());
            return;
        }
        if (event.type() == AgentEventType.AGENT_MESSAGE && event.payload() instanceof AgentMessagePayload payload) {
            persistAssistantMessage(session, event, payload);
            return;
        }
        if (event.type() == AgentEventType.COMMAND_STARTED) {
            updateCommandStatus(event, AgentCommandDbStatus.RUNNING);
            return;
        }
        if (event.type() == AgentEventType.COMMAND_COMPLETED) {
            updateCommandStatus(event, AgentCommandDbStatus.SUCCEEDED);
            return;
        }
        if (event.type() == AgentEventType.ERROR) {
            persistErrorMessage(session, event);
        }
    }

    private void persistAssistantMessage(AgentSessionDO session, AgentEvent event, AgentMessagePayload payload) {
        String messageId = payload.messageId() == null || payload.messageId().isBlank()
                ? idFactory.messageId() : payload.messageId();
        if (messageMapper.selectByMessageId(messageId) != null) {
            return;
        }
        AgentMessageDO message = new AgentMessageDO();
        message.setTenantId(session.getTenantId());
        message.setMessageId(messageId);
        message.setSessionId(session.getId());
        message.setRole(AgentMessageRole.ASSISTANT.name());
        message.setMessageType(AgentMessageType.TEXT.name());
        message.setContent(payload.content());
        message.setEventSeq(event.seq());
        message.setMessageStatus(AgentMessageStatus.FINAL.name());
        message.setNativeItemId(stringExtension(event, "nativeItemId"));
        message.setCreateSource(AgentMessageCreateSource.AGENT_EVENT.name());
        messageMapper.insert(message);
    }

    private void persistErrorMessage(AgentSessionDO session, AgentEvent event) {
        String content = event.payload() instanceof AgentErrorPayload payload ? payload.message() : "Agent error";
        AgentMessageDO message = new AgentMessageDO();
        message.setTenantId(session.getTenantId());
        message.setMessageId(idFactory.messageId());
        message.setSessionId(session.getId());
        message.setRole(AgentMessageRole.SYSTEM.name());
        message.setMessageType(AgentMessageType.ERROR.name());
        message.setContent(content);
        message.setEventSeq(event.seq());
        message.setMessageStatus(AgentMessageStatus.FINAL.name());
        message.setCreateSource(AgentMessageCreateSource.AGENT_EVENT.name());
        messageMapper.insert(message);
        session.setErrorMessage(content);
    }

    private void updateCommandStatus(AgentEvent event, AgentCommandDbStatus status) {
        String commandId = commandId(event);
        if (commandId == null) {
            return;
        }
        AgentCommandDO command = commandMapper.selectByCommandId(commandId);
        if (command == null) {
            return;
        }
        if (status == AgentCommandDbStatus.RUNNING
                && AgentCommandDbStatus.SUCCEEDED.name().equals(command.getCommandStatus())) {
            return;
        }
        command.setCommandStatus(status.name());
        if (status == AgentCommandDbStatus.SUCCEEDED) {
            command.setCompletedTime(LocalDateTime.now());
        }
        commandMapper.updateById(command);
    }

    private String commandId(AgentEvent event) {
        if (event.payload() instanceof CommandOutputPayload payload && payload.commandId() != null) {
            return payload.commandId();
        }
        Object value = event.extensions().get("commandId");
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private String stringExtension(AgentEvent event, String key) {
        Object value = event.extensions().get(key);
        return value instanceof String text ? text : null;
    }
}
