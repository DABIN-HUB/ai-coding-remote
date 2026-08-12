package com.wangbin.ai.module.agent.service.event;

import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.command.CommandAckStatus;
import com.wangbin.ai.agent.contract.coordination.AgentEventIngressPayload;
import com.wangbin.ai.agent.contract.coordination.CommandAckIngressPayload;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.event.*;
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
import com.wangbin.ai.module.agent.enums.*;
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import com.wangbin.ai.module.agent.service.change.AgentChangeSetService;
import com.wangbin.ai.module.agent.service.permission.AgentPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.SESSION_NOT_EXISTS;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentEventIngressServiceImpl implements AgentEventIngressService {

    private final AgentSessionMapper sessionMapper;
    private final AgentCommandMapper commandMapper;
    private final AgentDeviceMapper deviceMapper;
    private final AgentProjectMapper projectMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentEventReliabilityPolicy reliabilityPolicy;
    private final AgentIdFactory idFactory;
    private final AgentPermissionService permissionService;
    private final AgentChangeSetService changeSetService;

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
        if (!isAgentEventIdentityValid(payload, event, session)) {
            return;
        }
        long lastSeq = session.getLastEventSeq() == null ? 0L : session.getLastEventSeq();
        if (event.seq() <= lastSeq) {
            return;
        }
        applyEvent(session, event);
        session.setLastEventSeq(event.seq());
        session.setLastActiveTime(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleCommandAck(CommandAckIngressPayload payload) {
        CommandAck ack = payload.ack();
        AgentCommandDO command = commandMapper.selectByCommandId(ack.commandId());
        if (command == null) {
            return;
        }
        AgentSessionDO session = sessionMapper.selectById(command.getSessionId());
        AgentDeviceDO device = deviceMapper.selectById(command.getDeviceId());
        if (!isCommandAckIdentityValid(payload, ack, command, session, device)) {
            return;
        }
        command.setAckCode(ack.code());
        command.setAckMessage(ack.message());
        command.setAckedTime(LocalDateTime.now());
        if (ack.status() == CommandAckStatus.ACCEPTED || ack.status() == CommandAckStatus.DUPLICATE) {
            transitionCommand(command, AgentCommandDbStatus.ACKED, null);
        } else if (ack.status() == CommandAckStatus.REJECTED) {
            transitionCommand(command, AgentCommandDbStatus.REJECTED, ack.message());
        } else if (ack.status() == CommandAckStatus.FAILED) {
            transitionCommand(command, AgentCommandDbStatus.FAILED, ack.message());
        }
        commandMapper.updateById(command);
        permissionService.handleDecisionCommandAck(command, ack);
    }

    private void applyEvent(AgentSessionDO session, AgentEvent event) {
        String platformCommandId = platformCommandId(event);
        if (event.type() == AgentEventType.SESSION_STARTED) {
            session.setSessionStatus(AgentSessionDbStatus.RUNNING.name());
            session.setStartedTime(LocalDateTime.now());
            if (event.payload() instanceof SessionPayload payload) {
                session.setNativeSessionId(payload.nativeSessionId());
            }
            markCommandRunning(platformCommandId);
            return;
        }
        if (event.type() == AgentEventType.SESSION_STATE_CHANGED && event.payload() instanceof SessionPayload payload) {
            applySessionStatus(session, payload.status());
            if (payload.status() == AgentSessionStatus.RUNNING) {
                markCommandRunning(platformCommandId);
            }
            return;
        }
        if (event.type() == AgentEventType.SESSION_IDLE) {
            session.setSessionStatus(AgentSessionDbStatus.IDLE.name());
            completeCommand(platformCommandId);
            return;
        }
        if (event.type() == AgentEventType.SESSION_COMPLETED) {
            session.setSessionStatus(AgentSessionDbStatus.CLOSED.name());
            session.setClosedTime(LocalDateTime.now());
            return;
        }
        if (event.type() == AgentEventType.PERMISSION_REQUIRED
                && event.payload() instanceof PermissionRequiredPayload payload) {
            permissionService.handlePermissionRequired(session, event, platformCommandId, payload);
            session.setSessionStatus(AgentSessionDbStatus.WAITING_PERMISSION.name());
            return;
        }
        if (event.type() == AgentEventType.PERMISSION_RESOLVED
                && event.payload() instanceof PermissionResolvedPayload payload) {
            permissionService.handlePermissionResolved(payload);
            if (AgentSessionDbStatus.WAITING_PERMISSION.name().equals(session.getSessionStatus())) {
                session.setSessionStatus(AgentSessionDbStatus.RUNNING.name());
            }
            return;
        }
        if (event.type() == AgentEventType.CHANGE_SET_FINALIZED
                && event.payload() instanceof ChangeSetFinalizedPayload payload) {
            changeSetService.handleChangeSetFinalized(session, event, platformCommandId, payload);
            return;
        }
        if (event.type() == AgentEventType.AGENT_MESSAGE && event.payload() instanceof AgentMessagePayload payload) {
            markCommandRunning(platformCommandId);
            persistAssistantMessage(session, event, payload);
            return;
        }
        if (event.type() == AgentEventType.COMMAND_STARTED || event.type() == AgentEventType.COMMAND_COMPLETED
                || event.type() == AgentEventType.COMMAND_OUTPUT) {
            markCommandRunning(platformCommandId);
            return;
        }
        if (event.type() == AgentEventType.ERROR) {
            persistErrorMessage(session, event);
            if (event.payload() instanceof AgentErrorPayload payload && !payload.retryable()) {
                session.setSessionStatus(AgentSessionDbStatus.FAILED.name());
                failCommand(platformCommandId, payload.message());
            }
        }
    }

    private void applySessionStatus(AgentSessionDO session, AgentSessionStatus status) {
        if (status == AgentSessionStatus.RUNNING) {
            session.setSessionStatus(AgentSessionDbStatus.RUNNING.name());
        } else if (status == AgentSessionStatus.IDLE) {
            session.setSessionStatus(AgentSessionDbStatus.IDLE.name());
        } else if (status == AgentSessionStatus.FAILED) {
            session.setSessionStatus(AgentSessionDbStatus.FAILED.name());
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
        AgentCommandDO command = command(platformCommandId(event));
        if (command != null && command.getSessionId().equals(session.getId())) {
            message.setCommandId(command.getId());
        }
        message.setNativeItemId(stringExtension(payload.extensions(), AgentEventExtensionKeys.NATIVE_ITEM_ID));
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

    private void markCommandRunning(String commandId) {
        AgentCommandDO command = command(commandId);
        if (command == null) {
            return;
        }
        transitionCommand(command, AgentCommandDbStatus.RUNNING, null);
        commandMapper.updateById(command);
    }

    private void completeCommand(String commandId) {
        AgentCommandDO command = command(commandId);
        if (command == null) {
            return;
        }
        transitionCommand(command, AgentCommandDbStatus.SUCCEEDED, null);
        commandMapper.updateById(command);
    }

    private void failCommand(String commandId, String message) {
        AgentCommandDO command = command(commandId);
        if (command == null) {
            return;
        }
        transitionCommand(command, AgentCommandDbStatus.FAILED, message);
        commandMapper.updateById(command);
    }

    private AgentCommandDO command(String commandId) {
        if (commandId == null) {
            return null;
        }
        return commandMapper.selectByCommandId(commandId);
    }

    private void transitionCommand(AgentCommandDO command, AgentCommandDbStatus target, String errorMessage) {
        AgentCommandDbStatus current = AgentCommandDbStatus.valueOf(command.getCommandStatus());
        if (!canTransition(current, target)) {
            return;
        }
        command.setCommandStatus(target.name());
        if (target == AgentCommandDbStatus.SUCCEEDED || target == AgentCommandDbStatus.FAILED
                || target == AgentCommandDbStatus.REJECTED || target == AgentCommandDbStatus.TIMEOUT) {
            command.setCompletedTime(LocalDateTime.now());
        }
        if (errorMessage != null && !errorMessage.isBlank()) {
            command.setErrorMessage(errorMessage);
        }
    }

    private boolean canTransition(AgentCommandDbStatus current, AgentCommandDbStatus target) {
        return switch (target) {
            case ACKED -> current == AgentCommandDbStatus.CREATED || current == AgentCommandDbStatus.ROUTING;
            case RUNNING -> current == AgentCommandDbStatus.CREATED || current == AgentCommandDbStatus.ROUTING
                    || current == AgentCommandDbStatus.ACKED;
            case SUCCEEDED -> current == AgentCommandDbStatus.RUNNING || current == AgentCommandDbStatus.ACKED;
            case FAILED -> current != AgentCommandDbStatus.SUCCEEDED && current != AgentCommandDbStatus.FAILED
                    && current != AgentCommandDbStatus.REJECTED && current != AgentCommandDbStatus.TIMEOUT;
            case REJECTED -> current == AgentCommandDbStatus.CREATED || current == AgentCommandDbStatus.ROUTING
                    || current == AgentCommandDbStatus.ACKED;
            case TIMEOUT -> current == AgentCommandDbStatus.CREATED || current == AgentCommandDbStatus.ROUTING;
            case CREATED, ROUTING, DELIVERED -> false;
        };
    }

    private String platformCommandId(AgentEvent event) {
        Object value = event.extensions().get(AgentEventExtensionKeys.PLATFORM_COMMAND_ID);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private String stringExtension(java.util.Map<String, Object> extensions, String key) {
        Object value = extensions.get(key);
        return value instanceof String text ? text : null;
    }

    private boolean isAgentEventIdentityValid(AgentEventIngressPayload payload, AgentEvent event,
                                              AgentSessionDO session) {
        if (!equals(session.getTenantId(), payload.tenantId()) || !equals(session.getTenantId(), event.tenantId())
                || !equals(session.getOwnerUserId(), payload.userId())
                || !equals(session.getOwnerUserId(), event.userId())) {
            log.warn("reject AgentEvent ingress identity mismatch: sessionId={}, eventId={}",
                    event.sessionId(), event.eventId());
            return false;
        }
        AgentDeviceDO device = deviceMapper.selectById(session.getDeviceId());
        AgentProjectDO project = projectMapper.selectById(session.getProjectId());
        if (device == null || project == null
                || !equals(device.getTenantId(), session.getTenantId())
                || !equals(project.getTenantId(), session.getTenantId())
                || !equals(device.getDeviceId(), payload.deviceId())
                || !equals(device.getDeviceId(), event.deviceId())
                || !equals(project.getProjectId(), event.projectId())) {
            log.warn("reject AgentEvent business identity mismatch: sessionId={}, eventId={}",
                    event.sessionId(), event.eventId());
            return false;
        }
        return true;
    }

    private boolean isCommandAckIdentityValid(CommandAckIngressPayload payload, CommandAck ack,
                                              AgentCommandDO command, AgentSessionDO session, AgentDeviceDO device) {
        if (session == null || device == null
                || !equals(command.getTenantId(), payload.tenantId())
                || !equals(session.getTenantId(), payload.tenantId())
                || !equals(command.getTenantId(), session.getTenantId())
                || !equals(device.getTenantId(), session.getTenantId())
                || !equals(command.getOwnerUserId(), payload.userId())
                || !equals(session.getOwnerUserId(), payload.userId())
                || !equals(command.getOwnerUserId(), session.getOwnerUserId())
                || !equals(command.getSessionId(), session.getId())
                || !equals(command.getDeviceId(), session.getDeviceId())
                || !equals(device.getDeviceId(), payload.deviceId())
                || !equals(device.getDeviceId(), ack.deviceId())
                || !equals(session.getSessionId(), ack.sessionId())) {
            log.warn("reject CommandAck ingress identity mismatch: commandId={}", ack.commandId());
            return false;
        }
        return true;
    }

    private boolean equals(Object left, Object right) {
        return left != null && left.equals(right);
    }
}
