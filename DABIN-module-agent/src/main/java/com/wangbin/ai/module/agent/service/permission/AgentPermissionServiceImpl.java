package com.wangbin.ai.module.agent.service.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.command.AgentCommand;
import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.command.CommandAckStatus;
import com.wangbin.ai.agent.contract.command.PermissionDecisionCommandPayload;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.DeviceRoutePayload;
import com.wangbin.ai.agent.contract.coordination.RelayCommandDispatchPayload;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.enums.PermissionResolutionStatus;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.PermissionRequiredPayload;
import com.wangbin.ai.agent.contract.event.PermissionResolvedPayload;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.module.agent.controller.admin.permission.vo.AgentPermissionDecideReqVO;
import com.wangbin.ai.module.agent.controller.admin.permission.vo.AgentPermissionPageReqVO;
import com.wangbin.ai.module.agent.controller.admin.permission.vo.AgentPermissionRespVO;
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
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.*;

@Service
@RequiredArgsConstructor
public class AgentPermissionServiceImpl implements AgentPermissionService {

    private static final String ROUTE_UNAVAILABLE_CODE = "DEVICE_ROUTE_UNAVAILABLE";

    private final AgentPermissionRequestMapper permissionMapper;
    private final AgentSessionMapper sessionMapper;
    private final AgentCommandMapper commandMapper;
    private final AgentDeviceMapper deviceMapper;
    private final AgentProjectMapper projectMapper;
    private final DeviceRouteLookupService routeLookupService;
    private final RelayCommandGateway relayCommandGateway;
    private final RedissonClient redissonClient;
    private final AgentControlPlaneProperties properties;
    private final AgentIdFactory idFactory;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public PageResult<AgentPermissionRespVO> getPermissionPage(AgentPermissionPageReqVO reqVO, Long userId) {
        Long sessionDbId = null;
        if (reqVO.getSessionId() != null && !reqVO.getSessionId().isBlank()) {
            sessionDbId = requireSession(reqVO.getSessionId(), userId).getId();
        }
        PageResult<AgentPermissionRequestDO> page = permissionMapper.selectPage(reqVO, userId, sessionDbId);
        return new PageResult<>(page.getList().stream().map(this::toRespVO).toList(), page.getTotal());
    }

    @Override
    public AgentPermissionRespVO getPermission(String permissionId, Long userId) {
        return toRespVO(requirePermission(permissionId, userId));
    }

    @Override
    public AgentPermissionRespVO decidePermission(AgentPermissionDecideReqVO reqVO, Long userId) {
        RLock lock = redissonClient.getLock(AgentCoordinationKeys.permissionDecisionLock(
                TenantContextHolder.getRequiredTenantId(), reqVO.getPermissionId()));
        boolean locked = false;
        try {
            locked = lock.tryLock(properties.getCommandIdempotencyLockWaitTime().toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                throw exception(PERMISSION_STATE_INVALID);
            }
            return transactionTemplate.execute(status -> decideLocked(reqVO, userId));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw exception(PERMISSION_STATE_INVALID);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void handlePermissionRequired(AgentSessionDO session, AgentEvent event, String platformCommandId,
                                         PermissionRequiredPayload payload) {
        if (permissionMapper.selectByPermissionId(payload.permissionId()) != null) {
            return;
        }
        AgentCommandDO promptCommand = platformCommandId == null ? null : commandMapper.selectByCommandId(platformCommandId);
        AgentPermissionRequestDO permission = new AgentPermissionRequestDO();
        permission.setTenantId(session.getTenantId());
        permission.setPermissionId(payload.permissionId());
        permission.setSessionId(session.getId());
        permission.setCommandId(promptCommand == null ? null : promptCommand.getId());
        permission.setDeviceId(session.getDeviceId());
        permission.setProjectId(session.getProjectId());
        permission.setOwnerUserId(session.getOwnerUserId());
        permission.setPermissionType(payload.permissionType().name());
        permission.setPermissionStatus(AgentPermissionStatus.PENDING.name());
        permission.setTitle(payload.title());
        permission.setReason(payload.reason());
        permission.setRequestJson(writeJson(payload.detail()));
        permission.setRequestedTime(LocalDateTime.now());
        permissionMapper.insert(permission);
    }

    @Override
    public void handlePermissionResolved(PermissionResolvedPayload payload) {
        AgentPermissionRequestDO permission = permissionMapper.selectByPermissionId(payload.permissionId());
        if (permission == null || isFinal(permission.getPermissionStatus())) {
            return;
        }
        AgentPermissionStatus status = toPermissionStatus(payload.resolutionStatus());
        permission.setPermissionStatus(status.name());
        if (payload.decision() != null) {
            permission.setDecision(payload.decision().name());
        }
        permission.setResolvedTime(LocalDateTime.now());
        AgentCommandDO decisionCommand = payload.decisionCommandId() == null
                ? null : commandMapper.selectByCommandId(payload.decisionCommandId());
        if (decisionCommand != null) {
            permission.setDecisionCommandId(decisionCommand.getId());
            completeDecisionCommand(decisionCommand);
        } else if (permission.getDecisionCommandId() != null) {
            AgentCommandDO storedCommand = commandMapper.selectById(permission.getDecisionCommandId());
            if (storedCommand != null) {
                completeDecisionCommand(storedCommand);
            }
        }
        permissionMapper.updateById(permission);
        AgentSessionDO session = sessionMapper.selectById(permission.getSessionId());
        if (session != null && AgentSessionDbStatus.WAITING_PERMISSION.name().equals(session.getSessionStatus())) {
            session.setSessionStatus(AgentSessionDbStatus.RUNNING.name());
            sessionMapper.updateById(session);
        }
    }

    @Override
    public void handleDecisionCommandAck(AgentCommandDO command, CommandAck ack) {
        AgentPermissionRequestDO permission = permissionMapper.selectByDecisionCommandId(command.getId());
        if (permission == null || isFinal(permission.getPermissionStatus())) {
            return;
        }
        if (ack.status() == CommandAckStatus.ACCEPTED || ack.status() == CommandAckStatus.DUPLICATE) {
            if (AgentPermissionStatus.DECISION_ROUTING.name().equals(permission.getPermissionStatus())) {
                permission.setPermissionStatus(AgentPermissionStatus.DECISION_SENT.name());
                permissionMapper.updateById(permission);
            }
            return;
        }
        if (ack.status() == CommandAckStatus.REJECTED || ack.status() == CommandAckStatus.FAILED) {
            permission.setPermissionStatus(AgentPermissionStatus.FAILED.name());
            permission.setErrorMessage(ack.message());
            permissionMapper.updateById(permission);
        }
    }

    private AgentPermissionRespVO decideLocked(AgentPermissionDecideReqVO reqVO, Long userId) {
        AgentPermissionRequestDO permission = requirePermission(reqVO.getPermissionId(), userId);
        AgentPermissionStatus current = AgentPermissionStatus.valueOf(permission.getPermissionStatus());
        if (isFinal(current.name())) {
            if (reqVO.getDecision().name().equals(permission.getDecision())) {
                return toRespVO(permission);
            }
            throw exception(PERMISSION_ALREADY_RESOLVED);
        }
        if (current != AgentPermissionStatus.PENDING && current != AgentPermissionStatus.FAILED) {
            throw exception(PERMISSION_STATE_INVALID);
        }
        AgentSessionDO session = sessionMapper.selectById(permission.getSessionId());
        AgentDeviceDO device = deviceMapper.selectById(permission.getDeviceId());
        AgentProjectDO project = projectMapper.selectById(permission.getProjectId());
        if (session == null || device == null || project == null) {
            throw exception(PERMISSION_STATE_INVALID);
        }
        AgentCommandDO command = createDecisionCommand(permission, session, device, project, reqVO, userId);
        commandMapper.insert(command);
        permission.setDecision(reqVO.getDecision().name());
        permission.setDecisionReason(reqVO.getReason());
        permission.setDecisionUserId(userId);
        permission.setDecisionCommandId(command.getId());
        permission.setDecidedTime(LocalDateTime.now());
        permission.setPermissionStatus(AgentPermissionStatus.DECISION_ROUTING.name());
        permissionMapper.updateById(permission);
        DeviceRoutePayload route = routeLookupService.getRoute(device.getDeviceId());
        if (!isRouteValid(route, session.getTenantId(), device.getDeviceId())) {
            markDecisionDispatchFailed(permission, command, ROUTE_UNAVAILABLE_CODE, "Device route is unavailable");
            return toRespVO(permission);
        }
        command.setCommandStatus(AgentCommandDbStatus.ROUTING.name());
        command.setCreatedDispatchTime(LocalDateTime.now());
        commandMapper.updateById(command);
        try {
            relayCommandGateway.dispatch(new RelayCommandDispatchPayload(route.relayNodeId(), device.getDeviceId(),
                    route.connectionId(), session.getTenantId(), toAgentCommand(command, session, project, device,
                    reqVO), Instant.now()));
        } catch (RuntimeException ex) {
            markDecisionDispatchFailed(permission, command, "PERMISSION_DISPATCH_FAILED",
                    "Permission decision dispatch failed");
            return toRespVO(permission);
        }
        return toRespVO(permission);
    }

    private AgentCommandDO createDecisionCommand(AgentPermissionRequestDO permission, AgentSessionDO session,
                                                AgentDeviceDO device, AgentProjectDO project,
                                                AgentPermissionDecideReqVO reqVO, Long userId) {
        AgentCommandDO command = new AgentCommandDO();
        command.setTenantId(session.getTenantId());
        command.setCommandId(idFactory.commandId());
        command.setSessionId(session.getId());
        command.setDeviceId(device.getId());
        command.setProjectId(project.getId());
        command.setOwnerUserId(userId);
        command.setCommandType(commandType(reqVO.getDecision()).name());
        command.setCommandStatus(AgentCommandDbStatus.CREATED.name());
        command.setPayloadJson(writeJson(new PermissionDecisionCommandPayload(permission.getPermissionId(),
                reqVO.getDecision(), reqVO.getReason(), Map.of())));
        command.setCreator(String.valueOf(userId));
        command.setUpdater(String.valueOf(userId));
        return command;
    }

    private AgentCommand toAgentCommand(AgentCommandDO command, AgentSessionDO session, AgentProjectDO project,
                                        AgentDeviceDO device, AgentPermissionDecideReqVO reqVO) {
        return new AgentCommand(command.getCommandId(), command.getCommandId(), session.getTenantId(),
                session.getOwnerUserId(), device.getDeviceId(), project.getProjectId(), session.getSessionId(),
                AgentType.valueOf(session.getAgentType()), CommandType.valueOf(command.getCommandType()),
                new PermissionDecisionCommandPayload(reqVO.getPermissionId(), reqVO.getDecision(), reqVO.getReason(),
                        Map.of()),
                Instant.now(), Instant.now().plus(properties.getCommandAckTimeout()), Map.of());
    }

    private CommandType commandType(PermissionDecision decision) {
        return decision == PermissionDecision.APPROVED || decision == PermissionDecision.APPROVED_FOR_SESSION
                ? CommandType.APPROVE_PERMISSION : CommandType.REJECT_PERMISSION;
    }

    private void markDecisionDispatchFailed(AgentPermissionRequestDO permission, AgentCommandDO command,
                                            String code, String message) {
        command.setCommandStatus(AgentCommandDbStatus.FAILED.name());
        command.setAckCode(code);
        command.setErrorMessage(message);
        command.setCompletedTime(LocalDateTime.now());
        commandMapper.updateById(command);
        permission.setPermissionStatus(AgentPermissionStatus.FAILED.name());
        permission.setErrorMessage(message);
        permissionMapper.updateById(permission);
    }

    private void completeDecisionCommand(AgentCommandDO command) {
        AgentCommandDbStatus current = AgentCommandDbStatus.valueOf(command.getCommandStatus());
        if (current == AgentCommandDbStatus.SUCCEEDED || current == AgentCommandDbStatus.FAILED
                || current == AgentCommandDbStatus.REJECTED || current == AgentCommandDbStatus.TIMEOUT) {
            return;
        }
        command.setCommandStatus(AgentCommandDbStatus.SUCCEEDED.name());
        command.setCompletedTime(LocalDateTime.now());
        commandMapper.updateById(command);
    }

    private AgentPermissionStatus toPermissionStatus(PermissionResolutionStatus status) {
        return switch (status) {
            case APPROVED -> AgentPermissionStatus.APPROVED;
            case REJECTED -> AgentPermissionStatus.REJECTED;
            case CANCELLED -> AgentPermissionStatus.CANCELLED;
            case EXPIRED -> AgentPermissionStatus.EXPIRED;
            case FAILED -> AgentPermissionStatus.FAILED;
        };
    }

    private boolean isRouteValid(DeviceRoutePayload route, Long tenantId, String deviceId) {
        return route != null && tenantId.equals(route.tenantId()) && deviceId.equals(route.deviceId());
    }

    private boolean isFinal(String status) {
        return AgentPermissionStatus.APPROVED.name().equals(status)
                || AgentPermissionStatus.REJECTED.name().equals(status)
                || AgentPermissionStatus.CANCELLED.name().equals(status)
                || AgentPermissionStatus.EXPIRED.name().equals(status);
    }

    private AgentPermissionRequestDO requirePermission(String permissionId, Long userId) {
        AgentPermissionRequestDO permission = permissionMapper.selectByPermissionId(permissionId);
        if (permission == null) {
            throw exception(PERMISSION_NOT_EXISTS);
        }
        if (!permission.getOwnerUserId().equals(userId)) {
            throw exception(PERMISSION_ACCESS_DENIED);
        }
        return permission;
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw exception(PERMISSION_STATE_INVALID);
        }
    }

    private AgentPermissionRespVO toRespVO(AgentPermissionRequestDO permission) {
        AgentPermissionRespVO respVO = new AgentPermissionRespVO();
        respVO.setId(permission.getId());
        respVO.setPermissionId(permission.getPermissionId());
        respVO.setSessionId(permission.getSessionId());
        respVO.setCommandId(permission.getCommandId());
        respVO.setDeviceId(permission.getDeviceId());
        respVO.setProjectId(permission.getProjectId());
        respVO.setOwnerUserId(permission.getOwnerUserId());
        respVO.setPermissionType(permission.getPermissionType());
        respVO.setPermissionStatus(permission.getPermissionStatus());
        respVO.setTitle(permission.getTitle());
        respVO.setReason(permission.getReason());
        respVO.setRequestJson(permission.getRequestJson());
        respVO.setDecision(permission.getDecision());
        respVO.setDecisionReason(permission.getDecisionReason());
        respVO.setDecisionUserId(permission.getDecisionUserId());
        respVO.setDecisionCommandId(permission.getDecisionCommandId());
        respVO.setRequestedTime(permission.getRequestedTime());
        respVO.setDecidedTime(permission.getDecidedTime());
        respVO.setResolvedTime(permission.getResolvedTime());
        respVO.setErrorMessage(permission.getErrorMessage());
        return respVO;
    }
}
