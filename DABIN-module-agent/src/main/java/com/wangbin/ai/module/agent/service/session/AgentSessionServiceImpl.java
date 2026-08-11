package com.wangbin.ai.module.agent.service.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.command.AgentCommand;
import com.wangbin.ai.agent.contract.command.PromptCommandPayload;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.DeviceRoutePayload;
import com.wangbin.ai.agent.contract.coordination.RelayCommandDispatchPayload;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.module.agent.controller.admin.message.vo.AgentMessagePageReqVO;
import com.wangbin.ai.module.agent.controller.admin.message.vo.AgentMessageRespVO;
import com.wangbin.ai.module.agent.controller.admin.session.vo.*;
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
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import com.wangbin.ai.module.agent.service.command.DeviceRouteLookupService;
import com.wangbin.ai.module.agent.service.command.RelayCommandGateway;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.*;

@Service
@RequiredArgsConstructor
public class AgentSessionServiceImpl implements AgentSessionService {

    private static final String ROUTE_UNAVAILABLE_CODE = "DEVICE_ROUTE_UNAVAILABLE";

    private final AgentSessionMapper sessionMapper;
    private final AgentProjectMapper projectMapper;
    private final AgentDeviceMapper deviceMapper;
    private final AgentCommandMapper commandMapper;
    private final AgentMessageMapper messageMapper;
    private final DeviceRouteLookupService routeLookupService;
    private final RelayCommandGateway relayCommandGateway;
    private final RedissonClient redissonClient;
    private final AgentControlPlaneProperties properties;
    private final AgentIdFactory idFactory;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentSessionRespVO createSession(AgentSessionCreateReqVO reqVO, Long userId) {
        AgentProjectDO project = requireProject(reqVO.getProjectId(), userId);
        if (!project.isActive()) {
            throw exception(PROJECT_DISABLED);
        }
        AgentDeviceDO device = requireActiveDevice(project.getDeviceId(), userId);
        DeviceRoutePayload route = routeLookupService.getRoute(device.getDeviceId());
        if (!isRouteValid(route, TenantContextHolder.getRequiredTenantId(), device.getDeviceId())) {
            throw exception(DEVICE_OFFLINE);
        }
        AgentSessionDO session = new AgentSessionDO();
        session.setTenantId(TenantContextHolder.getRequiredTenantId());
        session.setSessionId(idFactory.sessionId());
        session.setDeviceId(device.getId());
        session.setProjectId(project.getId());
        session.setOwnerUserId(userId);
        session.setAgentType(reqVO.getAgentType().name());
        session.setSessionStatus(AgentSessionDbStatus.CREATED.name());
        session.setLastEventSeq(0L);
        session.setCreator(String.valueOf(userId));
        session.setUpdater(String.valueOf(userId));
        sessionMapper.insert(session);
        return toRespVO(session);
    }

    @Override
    public PageResult<AgentSessionRespVO> getSessionPage(AgentSessionPageReqVO reqVO, Long userId) {
        PageResult<AgentSessionDO> page = sessionMapper.selectPage(reqVO, userId);
        return new PageResult<>(page.getList().stream().map(this::toRespVO).toList(), page.getTotal());
    }

    @Override
    public AgentSessionRespVO getSession(String sessionId, Long userId) {
        return toRespVO(requireSession(sessionId, userId));
    }

    @Override
    public AgentCommandRespVO sendPrompt(AgentSessionSendPromptReqVO reqVO, Long userId) {
        if (reqVO.getClientRequestId() == null || reqVO.getClientRequestId().isBlank()) {
            return createAndDispatchPrompt(reqVO, userId);
        }
        RLock lock = redissonClient.getLock(AgentCoordinationKeys.commandIdempotencyLock(
                TenantContextHolder.getRequiredTenantId(), userId, reqVO.getSessionId(), reqVO.getClientRequestId()));
        boolean locked = false;
        try {
            locked = lock.tryLock(properties.getCommandIdempotencyLockWaitTime().toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                throw exception(COMMAND_DUPLICATE_REQUEST);
            }
            AgentSessionDO session = requireSession(reqVO.getSessionId(), userId);
            AgentCommandDO existing = commandMapper.selectByClientRequestId(session.getId(), userId,
                    reqVO.getClientRequestId());
            if (existing != null) {
                return toCommandRespVO(existing);
            }
            return createAndDispatchPrompt(reqVO, userId, session);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw exception(COMMAND_DUPLICATE_REQUEST);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public PageResult<AgentMessageRespVO> getMessagePage(String sessionId, AgentMessagePageReqVO reqVO, Long userId) {
        AgentSessionDO session = requireSession(sessionId, userId);
        PageResult<AgentMessageDO> page = messageMapper.selectPage(reqVO, session.getId());
        return new PageResult<>(page.getList().stream().map(this::toMessageRespVO).toList(), page.getTotal());
    }

    private AgentCommandRespVO createAndDispatchPrompt(AgentSessionSendPromptReqVO reqVO, Long userId) {
        return createAndDispatchPrompt(reqVO, userId, requireSession(reqVO.getSessionId(), userId));
    }

    private AgentCommandRespVO createAndDispatchPrompt(AgentSessionSendPromptReqVO reqVO, Long userId,
                                                       AgentSessionDO session) {
        AgentCommandDispatchContext context = transactionTemplate.execute(status ->
                createPromptCommand(reqVO, userId, session));
        if (context == null) {
            throw exception(COMMAND_STATE_INVALID);
        }
        return dispatchPromptCommand(context);
    }

    private AgentCommandDispatchContext createPromptCommand(AgentSessionSendPromptReqVO reqVO, Long userId,
                                                           AgentSessionDO session) {
        if (session.isClosed()) {
            throw exception(SESSION_CLOSED);
        }
        AgentProjectDO project = requireProject(session.getProjectId(), userId);
        AgentDeviceDO device = requireActiveDevice(session.getDeviceId(), userId);
        AgentCommandDO command = createCommand(session, project, device, reqVO, userId);
        commandMapper.insert(command);
        messageMapper.insert(createUserMessage(session, command, reqVO.getContent(), userId));
        return new AgentCommandDispatchContext(session, project, device, command, reqVO.getContent());
    }

    private AgentCommandRespVO dispatchPromptCommand(AgentCommandDispatchContext context) {
        AgentSessionDO session = context.session();
        AgentProjectDO project = context.project();
        AgentDeviceDO device = context.device();
        AgentCommandDO command = context.command();
        DeviceRoutePayload route = routeLookupService.getRoute(device.getDeviceId());
        if (!isRouteValid(route, session.getTenantId(), device.getDeviceId())) {
            markCommandFailed(command.getCommandId(), ROUTE_UNAVAILABLE_CODE, "Device route is unavailable");
            return toCommandRespVO(commandMapper.selectByCommandId(command.getCommandId()));
        }
        markCommandRouting(command.getCommandId());
        try {
            relayCommandGateway.dispatch(new RelayCommandDispatchPayload(route.relayNodeId(), device.getDeviceId(),
                    route.connectionId(), session.getTenantId(), toAgentCommand(command, session, project, device,
                    context.prompt()), Instant.now()));
        } catch (RuntimeException ex) {
            markCommandFailed(command.getCommandId(), "COMMAND_DISPATCH_FAILED", "Command dispatch failed");
            throw exception(COMMAND_DISPATCH_FAILED);
        }
        return toCommandRespVO(commandMapper.selectByCommandId(command.getCommandId()));
    }

    private AgentCommandDO createCommand(AgentSessionDO session, AgentProjectDO project, AgentDeviceDO device,
                                         AgentSessionSendPromptReqVO reqVO, Long userId) {
        AgentCommandDO command = new AgentCommandDO();
        command.setTenantId(session.getTenantId());
        command.setCommandId(idFactory.commandId());
        command.setSessionId(session.getId());
        command.setDeviceId(device.getId());
        command.setProjectId(project.getId());
        command.setOwnerUserId(userId);
        command.setCommandType(CommandType.PROMPT.name());
        command.setCommandStatus(AgentCommandDbStatus.CREATED.name());
        command.setRequestId(reqVO.getClientRequestId());
        command.setPayloadJson(writePayload(new PromptCommandPayload(reqVO.getContent(), Map.of())));
        command.setCreator(String.valueOf(userId));
        command.setUpdater(String.valueOf(userId));
        return command;
    }

    private AgentMessageDO createUserMessage(AgentSessionDO session, AgentCommandDO command, String content,
                                             Long userId) {
        AgentMessageDO message = new AgentMessageDO();
        message.setTenantId(session.getTenantId());
        message.setMessageId(idFactory.messageId());
        message.setSessionId(session.getId());
        message.setCommandId(command.getId());
        message.setRole(AgentMessageRole.USER.name());
        message.setMessageType(AgentMessageType.TEXT.name());
        message.setContent(content);
        message.setMessageStatus(AgentMessageStatus.FINAL.name());
        message.setCreateSource(AgentMessageCreateSource.USER_COMMAND.name());
        message.setCreator(String.valueOf(userId));
        message.setUpdater(String.valueOf(userId));
        return message;
    }

    private AgentCommand toAgentCommand(AgentCommandDO command, AgentSessionDO session, AgentProjectDO project,
                                        AgentDeviceDO device, String prompt) {
        return new AgentCommand(command.getCommandId(), command.getCommandId(), session.getTenantId(),
                session.getOwnerUserId(), device.getDeviceId(), project.getProjectId(), session.getSessionId(),
                AgentType.valueOf(session.getAgentType()), CommandType.PROMPT,
                new PromptCommandPayload(prompt, Map.of()), Instant.now(),
                Instant.now().plus(properties.getCommandAckTimeout()), Map.of("workspacePath", project.getWorkspacePath()));
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw exception(COMMAND_STATE_INVALID);
        }
    }

    private void markCommandRouting(String commandId) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentCommandDO command = commandMapper.selectByCommandId(commandId);
            if (command == null) {
                throw exception(COMMAND_NOT_EXISTS);
            }
            command.setCommandStatus(AgentCommandDbStatus.ROUTING.name());
            command.setCreatedDispatchTime(LocalDateTime.now());
            commandMapper.updateById(command);
        });
    }

    private void markCommandFailed(String commandId, String code, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentCommandDO command = commandMapper.selectByCommandId(commandId);
            if (command == null) {
                throw exception(COMMAND_NOT_EXISTS);
            }
            command.setCommandStatus(AgentCommandDbStatus.FAILED.name());
            command.setAckCode(code);
            command.setErrorMessage(message);
            command.setCompletedTime(LocalDateTime.now());
            commandMapper.updateById(command);
        });
    }

    private boolean isRouteValid(DeviceRoutePayload route, Long tenantId, String deviceId) {
        return route != null && tenantId.equals(route.tenantId()) && deviceId.equals(route.deviceId());
    }

    private AgentSessionDO requireSession(String sessionId, Long userId) {
        AgentSessionDO session = sessionMapper.selectBySessionId(sessionId);
        if (session == null) {
            throw exception(SESSION_NOT_EXISTS);
        }
        if (!session.getOwnerUserId().equals(userId)) {
            throw exception(SESSION_ACCESS_DENIED);
        }
        return session;
    }

    private AgentProjectDO requireProject(Long projectId, Long userId) {
        AgentProjectDO project = projectMapper.selectById(projectId);
        if (project == null) {
            throw exception(PROJECT_NOT_EXISTS);
        }
        if (!project.getOwnerUserId().equals(userId)) {
            throw exception(PROJECT_ACCESS_DENIED);
        }
        return project;
    }

    private AgentDeviceDO requireActiveDevice(Long deviceId, Long userId) {
        AgentDeviceDO device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw exception(DEVICE_NOT_EXISTS);
        }
        if (!device.getOwnerUserId().equals(userId)) {
            throw exception(DEVICE_ACCESS_DENIED);
        }
        if (!device.isActive()) {
            throw exception(DEVICE_DISABLED);
        }
        return device;
    }

    private AgentSessionRespVO toRespVO(AgentSessionDO session) {
        AgentSessionRespVO respVO = new AgentSessionRespVO();
        respVO.setId(session.getId());
        respVO.setSessionId(session.getSessionId());
        respVO.setDeviceId(session.getDeviceId());
        respVO.setProjectId(session.getProjectId());
        respVO.setRuntimeId(session.getRuntimeId());
        respVO.setOwnerUserId(session.getOwnerUserId());
        respVO.setAgentType(session.getAgentType());
        respVO.setNativeSessionId(session.getNativeSessionId());
        respVO.setSessionStatus(session.getSessionStatus());
        respVO.setLastEventSeq(session.getLastEventSeq());
        respVO.setLastActiveTime(session.getLastActiveTime());
        respVO.setStartedTime(session.getStartedTime());
        respVO.setClosedTime(session.getClosedTime());
        respVO.setErrorMessage(session.getErrorMessage());
        return respVO;
    }

    private AgentCommandRespVO toCommandRespVO(AgentCommandDO command) {
        AgentCommandRespVO respVO = new AgentCommandRespVO();
        respVO.setId(command.getId());
        respVO.setCommandId(command.getCommandId());
        respVO.setSessionId(command.getSessionId());
        respVO.setCommandType(command.getCommandType());
        respVO.setCommandStatus(command.getCommandStatus());
        respVO.setRequestId(command.getRequestId());
        respVO.setAckCode(command.getAckCode());
        respVO.setAckMessage(command.getAckMessage());
        respVO.setAckedTime(command.getAckedTime());
        return respVO;
    }

    private AgentMessageRespVO toMessageRespVO(AgentMessageDO message) {
        AgentMessageRespVO respVO = new AgentMessageRespVO();
        respVO.setId(message.getId());
        respVO.setMessageId(message.getMessageId());
        respVO.setSessionId(message.getSessionId());
        respVO.setCommandId(message.getCommandId());
        respVO.setRole(message.getRole());
        respVO.setMessageType(message.getMessageType());
        respVO.setContent(message.getContent());
        respVO.setEventSeq(message.getEventSeq());
        respVO.setMessageStatus(message.getMessageStatus());
        respVO.setNativeItemId(message.getNativeItemId());
        respVO.setCreateSource(message.getCreateSource());
        respVO.setCreateTime(message.getCreateTime());
        return respVO;
    }
}
