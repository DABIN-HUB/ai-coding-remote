package com.wangbin.ai.module.agent.service.artifact;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.wangbin.ai.agent.contract.command.AgentCommand;
import com.wangbin.ai.agent.contract.command.ArtifactFetchCommandPayload;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.DeviceRoutePayload;
import com.wangbin.ai.agent.contract.coordination.RelayCommandDispatchPayload;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.ArtifactSourceType;
import com.wangbin.ai.agent.contract.enums.ArtifactStatus;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.common.util.json.JsonUtils;
import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPageReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPrepareUploadReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPrepareUploadRespVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactReportFailureReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactRequestFileReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactRespVO;
import com.wangbin.ai.module.agent.dal.dataobject.artifact.AgentArtifactDO;
import com.wangbin.ai.module.agent.dal.dataobject.change.AgentChangeSetDO;
import com.wangbin.ai.module.agent.dal.dataobject.change.AgentFileChangeDO;
import com.wangbin.ai.module.agent.dal.dataobject.command.AgentCommandDO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.dataobject.project.AgentProjectDO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;
import com.wangbin.ai.module.agent.dal.mysql.artifact.AgentArtifactMapper;
import com.wangbin.ai.module.agent.dal.mysql.change.AgentChangeSetMapper;
import com.wangbin.ai.module.agent.dal.mysql.change.AgentFileChangeMapper;
import com.wangbin.ai.module.agent.dal.mysql.command.AgentCommandMapper;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceMapper;
import com.wangbin.ai.module.agent.dal.mysql.project.AgentProjectMapper;
import com.wangbin.ai.module.agent.dal.mysql.session.AgentSessionMapper;
import com.wangbin.ai.module.agent.enums.AgentCommandDbStatus;
import com.wangbin.ai.module.agent.enums.AgentSessionDbStatus;
import com.wangbin.ai.module.agent.framework.config.AgentArtifactProperties;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import com.wangbin.ai.module.agent.service.command.DeviceRouteLookupService;
import com.wangbin.ai.module.agent.service.command.RelayCommandGateway;
import com.wangbin.ai.module.agent.service.device.DeviceCredentialAuthService;
import com.wangbin.ai.module.agent.service.device.DeviceCredentialIdentity;
import com.wangbin.ai.module.infra.api.file.FileApi;
import com.wangbin.ai.module.infra.api.file.dto.FileClientCapabilityRespDTO;
import com.wangbin.ai.module.infra.api.file.dto.FileRespDTO;
import com.wangbin.ai.module.infra.api.file.dto.FileUploadReqDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentArtifactServiceImpl implements AgentArtifactService {

    private static final String ROUTE_UNAVAILABLE_CODE = "DEVICE_ROUTE_UNAVAILABLE";
    private static final String DISPATCH_FAILED_CODE = "ARTIFACT_DISPATCH_FAILED";
    private static final String ARTIFACT_DIRECTORY = "agent/artifact";
    private static final String UPLOAD_PATH = "/agent/artifact/upload";
    private static final String SHA_256_ALGORITHM = "SHA-256";
    private static final int TICKET_BYTES = 32;
    private static final int ERROR_MESSAGE_MAX = 1024;

    private final AgentArtifactMapper artifactMapper;
    private final AgentFileChangeMapper fileChangeMapper;
    private final AgentChangeSetMapper changeSetMapper;
    private final AgentSessionMapper sessionMapper;
    private final AgentCommandMapper commandMapper;
    private final AgentDeviceMapper deviceMapper;
    private final AgentProjectMapper projectMapper;
    private final DeviceRouteLookupService routeLookupService;
    private final RelayCommandGateway relayCommandGateway;
    private final DeviceCredentialAuthService credentialAuthService;
    private final FileApi fileApi;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final AgentControlPlaneProperties controlPlaneProperties;
    private final AgentArtifactProperties artifactProperties;
    private final AgentIdFactory idFactory;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public AgentArtifactRespVO requestFile(AgentArtifactRequestFileReqVO reqVO, Long userId) {
        if (StrUtil.isBlank(reqVO.getClientRequestId())) {
            return createAndDispatch(reqVO, userId);
        }
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        RLock lock = redissonClient.getLock(AgentCoordinationKeys.artifactRequestLock(tenantId, userId,
                reqVO.getFileChangeId(), reqVO.getClientRequestId()));
        boolean locked = false;
        try {
            locked = lock.tryLock(controlPlaneProperties.getCommandIdempotencyLockWaitTime().toMillis(),
                    TimeUnit.MILLISECONDS);
            if (!locked) {
                throw exception(COMMAND_DUPLICATE_REQUEST);
            }
            AgentFileChangeDO fileChange = requireFileChange(reqVO.getFileChangeId());
            AgentArtifactDO existing = artifactMapper.selectByClientRequestId(userId, fileChange.getId(),
                    reqVO.getClientRequestId());
            if (existing != null) {
                return toRespVO(existing);
            }
            return createAndDispatch(reqVO, userId, fileChange);
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
    public PageResult<AgentArtifactRespVO> getArtifactPage(AgentArtifactPageReqVO reqVO, Long userId) {
        Long sessionDbId = sessionDbId(reqVO.getSessionId(), userId);
        Long projectDbId = projectDbId(reqVO.getProjectId(), userId);
        PageResult<AgentArtifactDO> page = artifactMapper.selectPage(reqVO, userId, sessionDbId, projectDbId);
        return new PageResult<>(page.getList().stream().map(this::toRespVO).toList(), page.getTotal());
    }

    @Override
    public AgentArtifactRespVO getArtifact(String artifactId, Long userId) {
        return toRespVO(requireArtifact(artifactId, userId));
    }

    @Override
    public void download(String artifactId, Long userId, OutputStream outputStream) throws Exception {
        AgentArtifactDO artifact = requireArtifact(artifactId, userId);
        if (!ArtifactStatus.READY.name().equals(artifact.getArtifactStatus())) {
            throw exception(ARTIFACT_NOT_READY);
        }
        if (artifact.getExpireTime() != null && artifact.getExpireTime().isBefore(LocalDateTime.now())) {
            throw exception(ARTIFACT_EXPIRED);
        }
        if (artifact.getFileId() == null) {
            throw exception(ARTIFACT_FILE_STORE_FAILED);
        }
        FileRespDTO file = fileApi.getFile(artifact.getFileId());
        if (file == null) {
            throw exception(ARTIFACT_FILE_STORE_FAILED);
        }
        validateProviderDownloadLimit(file.getConfigId(), safeLong(artifact.getFileSize()));
        fileApi.writeFileContent(file.getConfigId(), file.getPath(), outputStream);
    }

    @Override
    public AgentArtifactPrepareUploadRespVO prepareUpload(Long tenantId, String credentialId, String credentialSecret,
                                                          AgentArtifactPrepareUploadReqVO reqVO) {
        DeviceCredentialIdentity identity = credentialAuthService.authenticate(tenantId, credentialId, credentialSecret);
        if (reqVO.getFileSize() > artifactProperties.getMaxFileSize()) {
            throw exception(ARTIFACT_SIZE_EXCEEDED);
        }
        Long oldTenantId = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(identity.tenantId());
        try {
            return transactionTemplate.execute(status -> prepareUploadLocked(identity, reqVO));
        } finally {
            restoreTenant(oldTenantId);
        }
    }

    @Override
    public AgentArtifactRespVO upload(String uploadTicket, InputStream inputStream, long contentLength) throws Exception {
        if (StrUtil.isBlank(uploadTicket)) {
            throw exception(ARTIFACT_UPLOAD_TICKET_INVALID);
        }
        UploadTicketPayload ticket = consumeTicket(uploadTicket);
        if (contentLength >= 0 && contentLength != ticket.expectedSize()) {
            throw exception(ARTIFACT_SIZE_MISMATCH);
        }
        RLock lock = redissonClient.getLock(AgentCoordinationKeys.artifactUploadLock(ticket.tenantId(),
                ticket.artifactId()));
        lock.lock();
        Long createdFileId = null;
        Long oldTenantId = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(ticket.tenantId());
        try {
            AgentArtifactDO artifact = artifactMapper.selectByArtifactId(ticket.artifactId());
            validateTicketArtifact(ticket, artifact);
            if (ArtifactStatus.READY.name().equals(artifact.getArtifactStatus())) {
                return toRespVO(artifact);
            }
            CountingDigestInputStream digestInputStream = new CountingDigestInputStream(inputStream);
            FileUploadReqDTO uploadReq = new FileUploadReqDTO();
            uploadReq.setConfigId(artifactProperties.getFileConfigId());
            uploadReq.setName(artifact.getFileName());
            uploadReq.setDirectory(ARTIFACT_DIRECTORY);
            uploadReq.setType(ticket.contentType());
            uploadReq.setSize(ticket.expectedSize());
            uploadReq.setInputStream(digestInputStream);
            FileRespDTO file = fileApi.createFile(uploadReq);
            createdFileId = file.getId();
            String actualSha256 = digestInputStream.sha256Hex();
            long actualSize = digestInputStream.count();
            if (actualSize != ticket.expectedSize()) {
                deleteCreatedFile(createdFileId);
                createdFileId = null;
                markFailed(artifact, "ARTIFACT_SIZE_MISMATCH", "Artifact upload size mismatch");
                throw exception(ARTIFACT_SIZE_MISMATCH);
            }
            if (!ticket.expectedSha256().equalsIgnoreCase(actualSha256)) {
                deleteCreatedFile(createdFileId);
                createdFileId = null;
                markFailed(artifact, "ARTIFACT_SHA256_MISMATCH", "Artifact upload SHA-256 mismatch");
                throw exception(ARTIFACT_SHA256_MISMATCH);
            }
            Long finalCreatedFileId = createdFileId;
            AgentArtifactDO updated = transactionTemplate.execute(status -> markReady(ticket, file, finalCreatedFileId));
            createdFileId = null;
            return toRespVO(updated);
        } catch (RuntimeException ex) {
            if (createdFileId != null) {
                deleteCreatedFile(createdFileId);
            }
            throw ex;
        } finally {
            if (oldTenantId == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(oldTenantId);
            }
            lock.unlock();
        }
    }

    @Override
    public void reportFailure(Long tenantId, String credentialId, String credentialSecret,
                              AgentArtifactReportFailureReqVO reqVO) {
        DeviceCredentialIdentity identity = credentialAuthService.authenticate(tenantId, credentialId, credentialSecret);
        RLock lock = redissonClient.getLock(AgentCoordinationKeys.artifactUploadLock(identity.tenantId(),
                reqVO.getArtifactId()));
        lock.lock();
        Long oldTenantId = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(identity.tenantId());
        try {
            transactionTemplate.executeWithoutResult(status -> {
                AgentArtifactDO artifact = artifactMapper.selectByArtifactId(reqVO.getArtifactId());
                if (artifact == null || !identity.tenantId().equals(artifact.getTenantId())
                        || !identity.ownerUserId().equals(artifact.getOwnerUserId())
                        || !identity.device().getId().equals(artifact.getDeviceId())) {
                    throw exception(ARTIFACT_ACCESS_DENIED);
                }
                if (ArtifactStatus.READY.name().equals(artifact.getArtifactStatus())
                        || ArtifactStatus.EXPIRED.name().equals(artifact.getArtifactStatus())) {
                    return;
                }
                markFailed(artifact, reqVO.getErrorCode(), sanitizeError(reqVO.getErrorMessage()));
            });
        } finally {
            restoreTenant(oldTenantId);
            lock.unlock();
        }
    }

    @Override
    public int cleanupExpired() {
        int count = 0;
        for (AgentArtifactDO artifact : artifactMapper.selectExpiredReady(LocalDateTime.now(),
                artifactProperties.getCleanupBatchSize())) {
            RLock lock = redissonClient.getLock(AgentCoordinationKeys.artifactCleanupLock(
                    TenantContextHolder.getRequiredTenantId(), artifact.getArtifactId()));
            if (!lock.tryLock()) {
                continue;
            }
            try {
                fileApi.deleteFileIfExists(artifact.getFileId());
                artifact.setArtifactStatus(ArtifactStatus.EXPIRED.name());
                artifactMapper.updateById(artifact);
                count++;
            } catch (Exception ex) {
                log.warn("artifact cleanup failed: artifactId={}, error={}", artifact.getArtifactId(), ex.getMessage());
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        return count;
    }

    private AgentArtifactRespVO createAndDispatch(AgentArtifactRequestFileReqVO reqVO, Long userId) {
        return createAndDispatch(reqVO, userId, requireFileChange(reqVO.getFileChangeId()));
    }

    private AgentArtifactRespVO createAndDispatch(AgentArtifactRequestFileReqVO reqVO, Long userId,
                                                  AgentFileChangeDO fileChange) {
        ArtifactDispatchContext context = transactionTemplate.execute(status ->
                createArtifactCommand(reqVO, userId, fileChange));
        if (context == null) {
            throw exception(ARTIFACT_SOURCE_INVALID);
        }
        dispatch(context);
        return toRespVO(artifactMapper.selectByArtifactId(context.artifact().getArtifactId()));
    }

    private ArtifactDispatchContext createArtifactCommand(AgentArtifactRequestFileReqVO reqVO, Long userId,
                                                          AgentFileChangeDO fileChange) {
        AgentChangeSetDO changeSet = requireChangeSet(fileChange.getChangeSetId(), userId);
        AgentSessionDO session = requireSession(changeSet.getSessionId(), userId);
        AgentCommandDO sourceCommand = requireSourceCommand(changeSet.getCommandId(), session, userId);
        AgentDeviceDO device = requireActiveDevice(session.getDeviceId(), userId);
        AgentProjectDO project = requireProject(session.getProjectId(), userId);
        validateFileChange(fileChange, changeSet, session);
        AgentArtifactDO artifact = new AgentArtifactDO();
        artifact.setTenantId(session.getTenantId());
        artifact.setArtifactId(idFactory.artifactId());
        artifact.setArtifactSourceType(ArtifactSourceType.CHANGE_SET_FILE.name());
        artifact.setArtifactStatus(ArtifactStatus.REQUESTED.name());
        artifact.setSessionId(session.getId());
        artifact.setSourceCommandId(sourceCommand.getId());
        artifact.setChangeSetId(changeSet.getId());
        artifact.setFileChangeId(fileChange.getId());
        artifact.setDeviceId(device.getId());
        artifact.setProjectId(project.getId());
        artifact.setOwnerUserId(userId);
        artifact.setRelativePath(fileChange.getRelativePath());
        artifact.setFileName(FileUtil.getName(fileChange.getRelativePath()));
        artifact.setClientRequestId(reqVO.getClientRequestId());
        artifact.setRequestedTime(LocalDateTime.now());
        artifact.setCreator(String.valueOf(userId));
        artifact.setUpdater(String.valueOf(userId));
        artifactMapper.insert(artifact);
        AgentCommandDO transferCommand = createTransferCommand(artifact, fileChange, changeSet, session, project,
                device, userId);
        commandMapper.insert(transferCommand);
        artifact.setTransferCommandId(transferCommand.getId());
        artifact.setArtifactStatus(ArtifactStatus.ROUTING.name());
        artifactMapper.updateById(artifact);
        return new ArtifactDispatchContext(artifact, fileChange, changeSet, session, project, device, transferCommand);
    }

    private void dispatch(ArtifactDispatchContext context) {
        DeviceRoutePayload route = routeLookupService.getRoute(context.device().getDeviceId());
        if (!isRouteValid(route, context.session().getTenantId(), context.device().getDeviceId())) {
            markDispatchFailed(context.artifact().getArtifactId(), context.command().getCommandId(),
                    ROUTE_UNAVAILABLE_CODE, "Device route is unavailable");
            return;
        }
        markCommandRouting(context.command().getCommandId());
        try {
            relayCommandGateway.dispatch(new RelayCommandDispatchPayload(route.relayNodeId(),
                    context.device().getDeviceId(), route.connectionId(), context.session().getTenantId(),
                    toAgentCommand(context), Instant.now()));
        } catch (RuntimeException ex) {
            markDispatchFailed(context.artifact().getArtifactId(), context.command().getCommandId(),
                    DISPATCH_FAILED_CODE, "Artifact command dispatch failed");
            throw exception(ARTIFACT_DISPATCH_FAILED);
        }
    }

    private AgentCommandDO createTransferCommand(AgentArtifactDO artifact, AgentFileChangeDO fileChange,
                                                 AgentChangeSetDO changeSet, AgentSessionDO session,
                                                 AgentProjectDO project, AgentDeviceDO device, Long userId) {
        AgentCommandDO command = new AgentCommandDO();
        command.setTenantId(session.getTenantId());
        command.setCommandId(idFactory.commandId());
        command.setSessionId(session.getId());
        command.setDeviceId(device.getId());
        command.setProjectId(project.getId());
        command.setOwnerUserId(userId);
        command.setCommandType(CommandType.FETCH_ARTIFACT.name());
        command.setCommandStatus(AgentCommandDbStatus.CREATED.name());
        command.setPayloadJson(JsonUtils.toJsonString(new ArtifactFetchCommandPayload(artifact.getArtifactId(),
                fileChange.getFileChangeId(), changeSet.getChangeSetId(),
                artifact.getRelativePath(), ArtifactSourceType.CHANGE_SET_FILE, Map.of())));
        command.setCreator(String.valueOf(userId));
        command.setUpdater(String.valueOf(userId));
        return command;
    }

    private AgentCommand toAgentCommand(ArtifactDispatchContext context) {
        ArtifactFetchCommandPayload payload = new ArtifactFetchCommandPayload(context.artifact().getArtifactId(),
                context.fileChange().getFileChangeId(), context.changeSet().getChangeSetId(),
                context.artifact().getRelativePath(), ArtifactSourceType.CHANGE_SET_FILE, Map.of());
        return new AgentCommand(context.command().getCommandId(), context.command().getCommandId(),
                context.session().getTenantId(), context.session().getOwnerUserId(), context.device().getDeviceId(),
                context.project().getProjectId(), context.session().getSessionId(),
                AgentType.valueOf(context.session().getAgentType()), CommandType.FETCH_ARTIFACT, payload,
                Instant.now(), Instant.now().plus(controlPlaneProperties.getCommandAckTimeout()), Map.of());
    }

    private AgentArtifactPrepareUploadRespVO prepareUploadLocked(DeviceCredentialIdentity identity,
                                                                 AgentArtifactPrepareUploadReqVO reqVO) {
        AgentArtifactDO artifact = artifactMapper.selectByArtifactId(reqVO.getArtifactId());
        if (artifact == null) {
            throw exception(ARTIFACT_NOT_EXISTS);
        }
        if (!identity.device().getId().equals(artifact.getDeviceId())) {
            throw exception(ARTIFACT_ACCESS_DENIED);
        }
        if (ArtifactStatus.READY.name().equals(artifact.getArtifactStatus())) {
            if (reqVO.getFileSize() == safeLong(artifact.getFileSize())
                    && reqVO.getSha256().equalsIgnoreCase(artifact.getSha256())) {
                AgentArtifactPrepareUploadRespVO respVO = new AgentArtifactPrepareUploadRespVO();
                respVO.setAlreadyReady(true);
                respVO.setUploadPath(UPLOAD_PATH);
                return respVO;
            }
            throw exception(ARTIFACT_SOURCE_INVALID);
        }
        if (!ArtifactStatus.ROUTING.name().equals(artifact.getArtifactStatus())
                && !ArtifactStatus.UPLOADING.name().equals(artifact.getArtifactStatus())) {
            throw exception(ARTIFACT_SOURCE_INVALID);
        }
        validateProviderUploadLimit(reqVO.getFileSize());
        artifact.setArtifactStatus(ArtifactStatus.UPLOADING.name());
        artifact.setContentType(reqVO.getContentType());
        artifact.setFileSize(reqVO.getFileSize());
        artifact.setSha256(reqVO.getSha256().toLowerCase());
        artifact.setSourceLastModifiedTime(reqVO.getSourceLastModifiedTime());
        artifact.setUploadStartedTime(LocalDateTime.now());
        artifactMapper.updateById(artifact);
        markTransferRunning(artifact.getTransferCommandId());
        String ticket = newTicket();
        UploadTicketPayload payload = new UploadTicketPayload(identity.tenantId(), identity.ownerUserId(),
                identity.device().getId(), artifact.getArtifactId(), reqVO.getFileSize(),
                reqVO.getSha256().toLowerCase(), reqVO.getContentType());
        stringRedisTemplate.opsForValue().set(AgentCoordinationKeys.artifactUploadTicket(ticket),
                JsonUtils.toJsonString(payload), artifactProperties.getUploadTicketTtl());
        AgentArtifactPrepareUploadRespVO respVO = new AgentArtifactPrepareUploadRespVO();
        respVO.setAlreadyReady(false);
        respVO.setUploadTicket(ticket);
        respVO.setUploadPath(UPLOAD_PATH);
        return respVO;
    }

    private UploadTicketPayload consumeTicket(String uploadTicket) {
        String key = AgentCoordinationKeys.artifactUploadTicket(uploadTicket);
        String value = stringRedisTemplate.opsForValue().getAndDelete(key);
        if (value == null) {
            throw exception(ARTIFACT_UPLOAD_TICKET_INVALID);
        }
        return JsonUtils.parseObject(value, UploadTicketPayload.class);
    }

    private void validateTicketArtifact(UploadTicketPayload ticket, AgentArtifactDO artifact) {
        if (artifact == null || !ticket.tenantId().equals(artifact.getTenantId())
                || !ticket.ownerUserId().equals(artifact.getOwnerUserId())
                || !ticket.deviceId().equals(artifact.getDeviceId())) {
            throw exception(ARTIFACT_ACCESS_DENIED);
        }
        if (ArtifactStatus.READY.name().equals(artifact.getArtifactStatus())) {
            if (ticket.expectedSize() == safeLong(artifact.getFileSize())
                    && ticket.expectedSha256().equalsIgnoreCase(artifact.getSha256())) {
                return;
            }
            throw exception(ARTIFACT_SOURCE_INVALID);
        }
        if (!ArtifactStatus.UPLOADING.name().equals(artifact.getArtifactStatus())
                || ticket.expectedSize() != safeLong(artifact.getFileSize())
                || !ticket.expectedSha256().equalsIgnoreCase(artifact.getSha256())) {
            throw exception(ARTIFACT_SOURCE_INVALID);
        }
    }

    private void validateProviderUploadLimit(long fileSize) {
        FileClientCapabilityRespDTO capability = fileApi.getFileClientCapability(artifactProperties.getFileConfigId());
        if (!Boolean.TRUE.equals(capability.getStreamingUpload())
                && fileSize > artifactProperties.getNonStreamingMaxFileSize()) {
            throw exception(ARTIFACT_NON_STREAMING_SIZE_EXCEEDED);
        }
    }

    private void validateProviderDownloadLimit(Long configId, long fileSize) {
        FileClientCapabilityRespDTO capability = fileApi.getFileClientCapability(configId);
        if (!Boolean.TRUE.equals(capability.getStreamingDownload())
                && fileSize > artifactProperties.getNonStreamingMaxFileSize()) {
            throw exception(ARTIFACT_NON_STREAMING_SIZE_EXCEEDED);
        }
    }

    private AgentArtifactDO markReady(UploadTicketPayload ticket, FileRespDTO file, Long fileId) {
        AgentArtifactDO artifact = artifactMapper.selectByArtifactId(ticket.artifactId());
        if (artifact == null) {
            throw exception(ARTIFACT_NOT_EXISTS);
        }
        if (ArtifactStatus.READY.name().equals(artifact.getArtifactStatus())) {
            return artifact;
        }
        if (!ArtifactStatus.UPLOADING.name().equals(artifact.getArtifactStatus())) {
            throw exception(ARTIFACT_SOURCE_INVALID);
        }
        artifact.setArtifactStatus(ArtifactStatus.READY.name());
        artifact.setFileId(fileId);
        artifact.setContentType(file.getType());
        artifact.setFileSize(ticket.expectedSize());
        artifact.setSha256(ticket.expectedSha256());
        artifact.setReadyTime(LocalDateTime.now());
        artifact.setExpireTime(artifact.getReadyTime().plus(artifactProperties.getRetention()));
        artifactMapper.updateById(artifact);
        completeTransferCommand(artifact.getTransferCommandId());
        return artifact;
    }

    private void deleteCreatedFile(Long fileId) {
        try {
            fileApi.deleteFile(fileId);
        } catch (Exception ex) {
            log.warn("failed to compensate artifact file: fileId={}, error={}", fileId, ex.getMessage());
        }
    }

    private void markFailed(AgentArtifactDO artifact, String code, String message) {
        AgentArtifactDO latest = artifactMapper.selectByArtifactId(artifact.getArtifactId());
        if (latest == null || ArtifactStatus.READY.name().equals(latest.getArtifactStatus())
                || ArtifactStatus.FAILED.name().equals(latest.getArtifactStatus())
                || ArtifactStatus.EXPIRED.name().equals(latest.getArtifactStatus())) {
            return;
        }
        latest.setArtifactStatus(ArtifactStatus.FAILED.name());
        latest.setErrorCode(code);
        latest.setErrorMessage(sanitizeError(message));
        artifactMapper.updateById(latest);
        failTransferCommand(latest.getTransferCommandId(), code, sanitizeError(message));
    }

    private void markDispatchFailed(String artifactId, String commandId, String code, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentArtifactDO artifact = artifactMapper.selectByArtifactId(artifactId);
            if (artifact != null) {
                markFailed(artifact, code, message);
            }
            failTransferCommand(commandId, code, message);
        });
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

    private void markTransferRunning(Long transferCommandId) {
        AgentCommandDO command = commandMapper.selectById(transferCommandId);
        if (command == null) {
            return;
        }
        AgentCommandDbStatus current = AgentCommandDbStatus.valueOf(command.getCommandStatus());
        if (current == AgentCommandDbStatus.CREATED || current == AgentCommandDbStatus.ROUTING
                || current == AgentCommandDbStatus.ACKED) {
            command.setCommandStatus(AgentCommandDbStatus.RUNNING.name());
            commandMapper.updateById(command);
        }
    }

    private void completeTransferCommand(Long transferCommandId) {
        AgentCommandDO command = commandMapper.selectById(transferCommandId);
        if (command == null) {
            return;
        }
        AgentCommandDbStatus current = AgentCommandDbStatus.valueOf(command.getCommandStatus());
        if (current == AgentCommandDbStatus.SUCCEEDED || current == AgentCommandDbStatus.FAILED
                || current == AgentCommandDbStatus.REJECTED || current == AgentCommandDbStatus.TIMEOUT) {
            return;
        }
        command.setCommandStatus(AgentCommandDbStatus.SUCCEEDED.name());
        command.setCompletedTime(LocalDateTime.now());
        commandMapper.updateById(command);
    }

    private void failTransferCommand(Long transferCommandId, String code, String message) {
        if (transferCommandId == null) {
            return;
        }
        AgentCommandDO command = commandMapper.selectById(transferCommandId);
        if (command == null) {
            return;
        }
        failTransferCommand(command.getCommandId(), code, message);
    }

    private void failTransferCommand(String commandId, String code, String message) {
        AgentCommandDO command = commandMapper.selectByCommandId(commandId);
        if (command == null) {
            return;
        }
        AgentCommandDbStatus current = AgentCommandDbStatus.valueOf(command.getCommandStatus());
        if (current == AgentCommandDbStatus.SUCCEEDED || current == AgentCommandDbStatus.FAILED
                || current == AgentCommandDbStatus.REJECTED || current == AgentCommandDbStatus.TIMEOUT) {
            return;
        }
        command.setCommandStatus(AgentCommandDbStatus.FAILED.name());
        command.setAckCode(code);
        command.setErrorMessage(message);
        command.setCompletedTime(LocalDateTime.now());
        commandMapper.updateById(command);
    }

    private void validateFileChange(AgentFileChangeDO fileChange, AgentChangeSetDO changeSet, AgentSessionDO session) {
        if (!fileChange.getChangeSetId().equals(changeSet.getId())
                || !fileChange.getSessionId().equals(session.getId())
                || !fileChange.getCommandId().equals(changeSet.getCommandId())) {
            throw exception(ARTIFACT_SOURCE_INVALID);
        }
        if (!AgentSessionDbStatus.IDLE.name().equals(session.getSessionStatus())) {
            throw exception(ARTIFACT_SESSION_NOT_IDLE);
        }
        if (com.wangbin.ai.agent.contract.enums.FileChangeType.DELETED.name().equals(fileChange.getChangeType())) {
            throw exception(ARTIFACT_SOURCE_DELETED);
        }
        if (Boolean.TRUE.equals(fileChange.getRedacted())) {
            throw exception(ARTIFACT_SOURCE_REDACTED);
        }
        if (StrUtil.isBlank(fileChange.getRelativePath()) || isPathSensitive(fileChange.getRelativePath())) {
            throw exception(ARTIFACT_SOURCE_INVALID);
        }
    }

    private boolean isPathSensitive(String relativePath) {
        String path = relativePath.replace('\\', '/').toLowerCase();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return ".env".equals(fileName)
                || fileName.startsWith(".env.")
                || fileName.endsWith(".pem")
                || fileName.endsWith(".key")
                || "id_rsa".equals(fileName)
                || ".id_rsa".equals(fileName)
                || "id_ed25519".equals(fileName)
                || ".id_ed25519".equals(fileName)
                || "auth.json".equals(fileName)
                || path.equals("credentials")
                || path.startsWith("credentials/")
                || path.endsWith("/credentials")
                || path.contains("/credentials/");
    }

    private boolean isRouteValid(DeviceRoutePayload route, Long tenantId, String deviceId) {
        return route != null && tenantId.equals(route.tenantId()) && deviceId.equals(route.deviceId());
    }

    private AgentFileChangeDO requireFileChange(String fileChangeId) {
        AgentFileChangeDO fileChange = fileChangeMapper.selectByFileChangeId(fileChangeId);
        if (fileChange == null) {
            throw exception(FILE_CHANGE_NOT_EXISTS);
        }
        return fileChange;
    }

    private AgentChangeSetDO requireChangeSet(Long changeSetId, Long userId) {
        AgentChangeSetDO changeSet = changeSetMapper.selectById(changeSetId);
        if (changeSet == null) {
            throw exception(CHANGE_SET_NOT_EXISTS);
        }
        if (!userId.equals(changeSet.getOwnerUserId())) {
            throw exception(CHANGE_SET_ACCESS_DENIED);
        }
        return changeSet;
    }

    private AgentSessionDO requireSession(Long sessionId, Long userId) {
        AgentSessionDO session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw exception(SESSION_NOT_EXISTS);
        }
        if (!userId.equals(session.getOwnerUserId())) {
            throw exception(SESSION_ACCESS_DENIED);
        }
        return session;
    }

    private AgentCommandDO requireSourceCommand(Long commandId, AgentSessionDO session, Long userId) {
        AgentCommandDO command = commandMapper.selectById(commandId);
        if (command == null) {
            throw exception(COMMAND_NOT_EXISTS);
        }
        if (!userId.equals(command.getOwnerUserId())
                || !session.getId().equals(command.getSessionId())
                || !session.getDeviceId().equals(command.getDeviceId())
                || !session.getProjectId().equals(command.getProjectId())) {
            throw exception(CHANGE_SET_COMMAND_MISMATCH);
        }
        return command;
    }

    private AgentDeviceDO requireActiveDevice(Long deviceId, Long userId) {
        AgentDeviceDO device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw exception(DEVICE_NOT_EXISTS);
        }
        if (!userId.equals(device.getOwnerUserId())) {
            throw exception(DEVICE_ACCESS_DENIED);
        }
        if (!device.isActive()) {
            throw exception(DEVICE_DISABLED);
        }
        return device;
    }

    private AgentProjectDO requireProject(Long projectId, Long userId) {
        AgentProjectDO project = projectMapper.selectById(projectId);
        if (project == null) {
            throw exception(PROJECT_NOT_EXISTS);
        }
        if (!userId.equals(project.getOwnerUserId())) {
            throw exception(PROJECT_ACCESS_DENIED);
        }
        return project;
    }

    private AgentArtifactDO requireArtifact(String artifactId, Long userId) {
        AgentArtifactDO artifact = artifactMapper.selectByArtifactId(artifactId);
        if (artifact == null) {
            throw exception(ARTIFACT_NOT_EXISTS);
        }
        if (!userId.equals(artifact.getOwnerUserId())) {
            throw exception(ARTIFACT_ACCESS_DENIED);
        }
        return artifact;
    }

    private Long sessionDbId(String sessionId, Long userId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        AgentSessionDO session = sessionMapper.selectBySessionId(sessionId);
        if (session == null) {
            throw exception(SESSION_NOT_EXISTS);
        }
        if (!userId.equals(session.getOwnerUserId())) {
            throw exception(ARTIFACT_ACCESS_DENIED);
        }
        return session.getId();
    }

    private Long projectDbId(String projectId, Long userId) {
        if (StrUtil.isBlank(projectId)) {
            return null;
        }
        AgentProjectDO project = projectMapper.selectByProjectId(projectId);
        if (project == null) {
            throw exception(PROJECT_NOT_EXISTS);
        }
        if (!userId.equals(project.getOwnerUserId())) {
            throw exception(ARTIFACT_ACCESS_DENIED);
        }
        return project.getId();
    }

    private long safeLong(Long value) {
        return value == null ? -1L : value;
    }

    private String newTicket() {
        byte[] bytes = new byte[TICKET_BYTES];
        secureRandom.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sanitizeError(String message) {
        if (message == null) {
            return "";
        }
        String sanitized = message.replace('\r', ' ').replace('\n', ' ');
        return StrUtil.maxLength(sanitized, ERROR_MESSAGE_MAX);
    }

    private void restoreTenant(Long oldTenantId) {
        if (oldTenantId == null) {
            TenantContextHolder.clear();
        } else {
            TenantContextHolder.setTenantId(oldTenantId);
        }
    }

    private AgentArtifactRespVO toRespVO(AgentArtifactDO artifact) {
        AgentArtifactRespVO respVO = new AgentArtifactRespVO();
        respVO.setId(artifact.getId());
        respVO.setArtifactId(artifact.getArtifactId());
        respVO.setSourceType(artifact.getArtifactSourceType());
        respVO.setStatus(artifact.getArtifactStatus());
        respVO.setSessionId(artifact.getSessionId());
        respVO.setSourceCommandId(artifact.getSourceCommandId());
        respVO.setTransferCommandId(artifact.getTransferCommandId());
        respVO.setChangeSetId(artifact.getChangeSetId());
        respVO.setFileChangeId(artifact.getFileChangeId());
        respVO.setRelativePath(artifact.getRelativePath());
        respVO.setFileName(artifact.getFileName());
        respVO.setContentType(artifact.getContentType());
        respVO.setFileSize(artifact.getFileSize());
        respVO.setSha256(artifact.getSha256());
        respVO.setFileId(artifact.getFileId());
        respVO.setRequestedTime(artifact.getRequestedTime());
        respVO.setReadyTime(artifact.getReadyTime());
        respVO.setExpireTime(artifact.getExpireTime());
        respVO.setErrorCode(artifact.getErrorCode());
        respVO.setErrorMessage(artifact.getErrorMessage());
        return respVO;
    }

    private record ArtifactDispatchContext(
            AgentArtifactDO artifact,
            AgentFileChangeDO fileChange,
            AgentChangeSetDO changeSet,
            AgentSessionDO session,
            AgentProjectDO project,
            AgentDeviceDO device,
            AgentCommandDO command
    ) {
    }

    private record UploadTicketPayload(
            Long tenantId,
            Long ownerUserId,
            Long deviceId,
            String artifactId,
            long expectedSize,
            String expectedSha256,
            String contentType
    ) {
    }

    private static final class CountingDigestInputStream extends FilterInputStream {

        private final MessageDigest digest;
        private long count;

        private CountingDigestInputStream(InputStream in) {
            super(in);
            try {
                this.digest = MessageDigest.getInstance(SHA_256_ALGORITHM);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 digest is not available", ex);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                digest.update((byte) value);
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                digest.update(b, off, read);
                count += read;
            }
            return read;
        }

        private long count() {
            return count;
        }

        private String sha256Hex() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
