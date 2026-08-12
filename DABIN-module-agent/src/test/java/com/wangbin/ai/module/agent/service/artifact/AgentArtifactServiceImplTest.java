package com.wangbin.ai.module.agent.service.artifact;

import com.wangbin.ai.agent.contract.command.ArtifactFetchCommandPayload;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.DeviceRoutePayload;
import com.wangbin.ai.agent.contract.coordination.RelayCommandDispatchPayload;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.enums.ArtifactSourceType;
import com.wangbin.ai.agent.contract.enums.ArtifactStatus;
import com.wangbin.ai.agent.contract.enums.CommandType;
import com.wangbin.ai.agent.contract.enums.FileChangeType;
import com.wangbin.ai.framework.common.exception.ServiceException;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPageReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPrepareUploadReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactReportFailureReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactRequestFileReqVO;
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
import com.wangbin.ai.module.agent.enums.DeviceStatus;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import com.wangbin.ai.framework.tenant.core.job.TenantJob;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentArtifactServiceImplTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final Long OTHER_USER_ID = 12L;
    private static final Long TEST_DEVICE_DB_ID = 20L;
    private static final Long TEST_PROJECT_DB_ID = 30L;
    private static final Long TEST_SESSION_DB_ID = 40L;
    private static final Long TEST_SOURCE_COMMAND_DB_ID = 50L;
    private static final Long TEST_TRANSFER_COMMAND_DB_ID = 60L;
    private static final Long TEST_CHANGE_SET_DB_ID = 70L;
    private static final Long TEST_FILE_CHANGE_DB_ID = 80L;
    private static final Long TEST_FILE_ID = 900L;
    private static final Long TEST_FILE_CONFIG_ID = 99L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_SOURCE_COMMAND_ID = "cmd-source";
    private static final String TEST_TRANSFER_COMMAND_ID = "cmd-transfer";
    private static final String TEST_CHANGE_SET_ID = "chg-1";
    private static final String TEST_FILE_CHANGE_ID = "fchg-1";
    private static final String TEST_ARTIFACT_ID = "art-1";
    private static final String TEST_CLIENT_REQUEST_ID = "client-request-1";
    private static final String TEST_RELATIVE_PATH = "src/App.java";
    private static final String TEST_RELAY_NODE_ID = "relay-1";
    private static final String TEST_CONNECTION_ID = "conn-1";
    private static final String TEST_CONTENT_TYPE = "text/plain";
    private static final byte[] TEST_CONTENT = "hello artifact".getBytes(StandardCharsets.UTF_8);

    private final AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
    private final AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
    private final AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
    private final AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
    private final AgentCommandMapper commandMapper = mock(AgentCommandMapper.class);
    private final AgentDeviceMapper deviceMapper = mock(AgentDeviceMapper.class);
    private final AgentProjectMapper projectMapper = mock(AgentProjectMapper.class);
    private final DeviceRouteLookupService routeLookupService = mock(DeviceRouteLookupService.class);
    private final RecordingRelayCommandGateway relayCommandGateway = new RecordingRelayCommandGateway();
    private final DeviceCredentialAuthService credentialAuthService = mock(DeviceCredentialAuthService.class);
    private final FileApi fileApi = mock(FileApi.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final AgentControlPlaneProperties controlPlaneProperties = new AgentControlPlaneProperties();
    private final AgentArtifactProperties artifactProperties = new AgentArtifactProperties();
    private final AgentIdFactory idFactory = mock(AgentIdFactory.class);
    private final AtomicReference<AgentArtifactDO> artifactStore = new AtomicReference<>();
    private final AtomicReference<AgentCommandDO> transferCommandStore = new AtomicReference<>();
    private final Map<String, String> redisValues = new HashMap<>();
    private AgentArtifactServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        TenantContextHolder.setTenantId(TEST_TENANT_ID);
        artifactProperties.setFileConfigId(TEST_FILE_CONFIG_ID);
        artifactProperties.setUploadTicketTtl(Duration.ofMinutes(5));
        artifactProperties.setRetention(Duration.ofDays(7));
        artifactProperties.setNonStreamingMaxFileSize(8L);
        artifactProperties.setCleanupInterval(Duration.ofSeconds(45));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            redisValues.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(valueOperations.getAndDelete(anyString())).thenAnswer(invocation ->
                redisValues.remove(invocation.getArgument(0)));
        when(fileApi.getFileClientCapability(any())).thenReturn(fileCapability(true, true));
        service = new AgentArtifactServiceImpl(artifactMapper, fileChangeMapper, changeSetMapper, sessionMapper,
                commandMapper, deviceMapper, projectMapper, routeLookupService, relayCommandGateway,
                credentialAuthService, fileApi, redissonClient, stringRedisTemplate, controlPlaneProperties,
                artifactProperties, idFactory, transactionTemplate());
        stubArtifactMapperStore();
        stubCommandMapperStore();
        stubGraph(AgentSessionDbStatus.IDLE, FileChangeType.MODIFIED, false);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void requestFileCreatesArtifactAndFetchCommandFromFileChangeContext() {
        when(idFactory.artifactId()).thenReturn(TEST_ARTIFACT_ID);
        when(idFactory.commandId()).thenReturn(TEST_TRANSFER_COMMAND_ID);

        var response = service.requestFile(request(TEST_CLIENT_REQUEST_ID), TEST_USER_ID);

        assertThat(response.getArtifactId()).isEqualTo(TEST_ARTIFACT_ID);
        assertThat(response.getStatus()).isEqualTo(ArtifactStatus.ROUTING.name());
        AgentArtifactDO artifact = artifactStore.get();
        assertThat(artifact.getSourceCommandId()).isEqualTo(TEST_SOURCE_COMMAND_DB_ID);
        assertThat(artifact.getTransferCommandId()).isEqualTo(TEST_TRANSFER_COMMAND_DB_ID);
        assertThat(artifact.getRelativePath()).isEqualTo(TEST_RELATIVE_PATH);
        assertThat(artifact.getFileName()).isEqualTo("App.java");
        AgentCommandDO transferCommand = transferCommandStore.get();
        assertThat(transferCommand.getCommandType()).isEqualTo(CommandType.FETCH_ARTIFACT.name());
        assertThat(transferCommand.getCommandStatus()).isEqualTo(AgentCommandDbStatus.ROUTING.name());
        assertThat(relayCommandGateway.payload.command().commandType()).isEqualTo(CommandType.FETCH_ARTIFACT);
        ArtifactFetchCommandPayload payload = (ArtifactFetchCommandPayload) relayCommandGateway.payload.command().payload();
        assertThat(payload.relativePath()).isEqualTo(TEST_RELATIVE_PATH);
        assertThat(payload.sourceType()).isEqualTo(ArtifactSourceType.CHANGE_SET_FILE);
    }

    @Test
    void requestFileReturnsExistingArtifactForSameClientRequestId() {
        AgentArtifactDO existing = artifact();
        existing.setClientRequestId(TEST_CLIENT_REQUEST_ID);
        when(artifactMapper.selectByClientRequestId(TEST_USER_ID, TEST_FILE_CHANGE_DB_ID, TEST_CLIENT_REQUEST_ID))
                .thenReturn(existing);

        var response = service.requestFile(request(TEST_CLIENT_REQUEST_ID), TEST_USER_ID);

        assertThat(response.getArtifactId()).isEqualTo(TEST_ARTIFACT_ID);
        verify(commandMapper, never()).insert(any(AgentCommandDO.class));
    }

    @Test
    void requestFileRejectsDeletedRedactedSensitiveAndNonIdleSource() {
        stubGraph(AgentSessionDbStatus.IDLE, FileChangeType.DELETED, false);
        assertThatThrownBy(() -> service.requestFile(request(null), TEST_USER_ID)).isInstanceOf(ServiceException.class);

        stubGraph(AgentSessionDbStatus.IDLE, FileChangeType.MODIFIED, true);
        assertThatThrownBy(() -> service.requestFile(request(null), TEST_USER_ID)).isInstanceOf(ServiceException.class);

        AgentFileChangeDO sensitive = fileChange(FileChangeType.MODIFIED, false);
        sensitive.setRelativePath(".env");
        when(fileChangeMapper.selectByFileChangeId(TEST_FILE_CHANGE_ID)).thenReturn(sensitive);
        assertThatThrownBy(() -> service.requestFile(request(null), TEST_USER_ID)).isInstanceOf(ServiceException.class);

        stubGraph(AgentSessionDbStatus.RUNNING, FileChangeType.MODIFIED, false);
        assertThatThrownBy(() -> service.requestFile(request(null), TEST_USER_ID)).isInstanceOf(ServiceException.class);
    }

    @Test
    void dispatchFailureMarksArtifactAndTransferCommandFailed() {
        when(idFactory.artifactId()).thenReturn(TEST_ARTIFACT_ID);
        when(idFactory.commandId()).thenReturn(TEST_TRANSFER_COMMAND_ID);
        relayCommandGateway.failDispatch = true;

        assertThatThrownBy(() -> service.requestFile(request(null), TEST_USER_ID)).isInstanceOf(ServiceException.class);

        assertThat(artifactStore.get().getArtifactStatus()).isEqualTo(ArtifactStatus.FAILED.name());
        assertThat(transferCommandStore.get().getCommandStatus()).isEqualTo(AgentCommandDbStatus.FAILED.name());
    }

    @Test
    void prepareUploadAuthenticatesDeviceAndStoresHashedOneTimeTicket() {
        stubCredential();
        artifactStore.set(artifact(ArtifactStatus.ROUTING));
        transferCommandStore.set(transferCommand(AgentCommandDbStatus.ACKED));

        var response = service.prepareUpload(TEST_TENANT_ID, "credential-id", "secret", prepareReq(sha256(TEST_CONTENT)));

        assertThat(response.getAlreadyReady()).isFalse();
        assertThat(response.getUploadTicket()).isNotBlank();
        assertThat(artifactStore.get().getArtifactStatus()).isEqualTo(ArtifactStatus.UPLOADING.name());
        assertThat(transferCommandStore.get().getCommandStatus()).isEqualTo(AgentCommandDbStatus.RUNNING.name());
        String redisKey = redisValues.keySet().iterator().next();
        assertThat(redisKey).startsWith("agent:artifact:upload:");
        assertThat(redisKey).doesNotContain(response.getUploadTicket());
    }

    @Test
    void prepareUploadRejectsOversizeForNonStreamingProviderButAllowsSmallFile() {
        stubCredential();
        artifactStore.set(artifact(ArtifactStatus.ROUTING));
        transferCommandStore.set(transferCommand(AgentCommandDbStatus.ACKED));
        when(fileApi.getFileClientCapability(TEST_FILE_CONFIG_ID)).thenReturn(fileCapability(false, true));

        assertThatThrownBy(() -> service.prepareUpload(TEST_TENANT_ID, "credential-id", "secret",
                prepareReq(sha256(TEST_CONTENT)))).isInstanceOf(ServiceException.class);

        byte[] smallContent = "small".getBytes(StandardCharsets.UTF_8);
        AgentArtifactPrepareUploadReqVO smallReq = prepareReq(sha256(smallContent));
        smallReq.setFileSize(smallContent.length);
        var response = service.prepareUpload(TEST_TENANT_ID, "credential-id", "secret", smallReq);

        assertThat(response.getAlreadyReady()).isFalse();
    }

    @Test
    void uploadStreamsIntoExistingFileApiAndMarksReady() throws Exception {
        stubCredential();
        artifactStore.set(artifact(ArtifactStatus.ROUTING));
        transferCommandStore.set(transferCommand(AgentCommandDbStatus.ACKED));
        String ticket = service.prepareUpload(TEST_TENANT_ID, "credential-id", "secret",
                prepareReq(sha256(TEST_CONTENT))).getUploadTicket();
        stubFileCreateConsumesStream();

        var response = service.upload(ticket, new ByteArrayInputStream(TEST_CONTENT), TEST_CONTENT.length);

        assertThat(response.getStatus()).isEqualTo(ArtifactStatus.READY.name());
        assertThat(response.getFileId()).isEqualTo(TEST_FILE_ID);
        assertThat(artifactStore.get().getFileId()).isEqualTo(TEST_FILE_ID);
        assertThat(transferCommandStore.get().getCommandStatus()).isEqualTo(AgentCommandDbStatus.SUCCEEDED.name());
        ArgumentCaptor<FileUploadReqDTO> uploadCaptor = ArgumentCaptor.forClass(FileUploadReqDTO.class);
        verify(fileApi).createFile(uploadCaptor.capture());
        assertThat(uploadCaptor.getValue().getConfigId()).isEqualTo(TEST_FILE_CONFIG_ID);
        assertThat(uploadCaptor.getValue().getDirectory()).isEqualTo("agent/artifact");
        assertThat(uploadCaptor.getValue().getName()).isEqualTo("App.java");
        assertThat(redisValues).isEmpty();
    }

    @Test
    void uploadMismatchDeletesCreatedFileAndFailsArtifact() throws Exception {
        stubCredential();
        artifactStore.set(artifact(ArtifactStatus.ROUTING));
        transferCommandStore.set(transferCommand(AgentCommandDbStatus.ACKED));
        String ticket = service.prepareUpload(TEST_TENANT_ID, "credential-id", "secret",
                prepareReq(sha256("different".getBytes(StandardCharsets.UTF_8)))).getUploadTicket();
        stubFileCreateConsumesStream();

        assertThatThrownBy(() -> service.upload(ticket, new ByteArrayInputStream(TEST_CONTENT), TEST_CONTENT.length))
                .isInstanceOf(ServiceException.class);

        verify(fileApi).deleteFile(TEST_FILE_ID);
        assertThat(artifactStore.get().getArtifactStatus()).isEqualTo(ArtifactStatus.FAILED.name());
        assertThat(transferCommandStore.get().getCommandStatus()).isEqualTo(AgentCommandDbStatus.FAILED.name());
    }

    @Test
    void uploadTicketCanOnlyBeConsumedOnce() throws Exception {
        stubCredential();
        artifactStore.set(artifact(ArtifactStatus.ROUTING));
        transferCommandStore.set(transferCommand(AgentCommandDbStatus.ACKED));
        String ticket = service.prepareUpload(TEST_TENANT_ID, "credential-id", "secret",
                prepareReq(sha256(TEST_CONTENT))).getUploadTicket();
        stubFileCreateConsumesStream();
        service.upload(ticket, new ByteArrayInputStream(TEST_CONTENT), TEST_CONTENT.length);

        assertThatThrownBy(() -> service.upload(ticket, new ByteArrayInputStream(TEST_CONTENT), TEST_CONTENT.length))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void downloadRequiresReadyArtifactAndUsesExistingFileApi() throws Exception {
        AgentArtifactDO artifact = artifact(ArtifactStatus.READY);
        artifact.setFileId(TEST_FILE_ID);
        artifact.setSha256(sha256(TEST_CONTENT));
        artifact.setExpireTime(LocalDateTime.now().plusDays(1));
        artifactStore.set(artifact);
        FileRespDTO file = fileResp();
        when(fileApi.getFile(TEST_FILE_ID)).thenReturn(file);
        doAnswer(invocation -> {
            OutputStream outputStream = invocation.getArgument(2);
            outputStream.write(TEST_CONTENT);
            return null;
        }).when(fileApi).writeFileContent(eq(file.getConfigId()), eq(file.getPath()), any(OutputStream.class));

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        service.download(TEST_ARTIFACT_ID, TEST_USER_ID, outputStream);

        assertThat(outputStream.toByteArray()).isEqualTo(TEST_CONTENT);
    }

    @Test
    void downloadRejectsOversizeForNonStreamingProvider() {
        AgentArtifactDO artifact = artifact(ArtifactStatus.READY);
        artifact.setFileId(TEST_FILE_ID);
        artifact.setFileSize((long) TEST_CONTENT.length);
        artifact.setExpireTime(LocalDateTime.now().plusDays(1));
        artifactStore.set(artifact);
        FileRespDTO file = fileResp();
        when(fileApi.getFile(TEST_FILE_ID)).thenReturn(file);
        when(fileApi.getFileClientCapability(TEST_FILE_CONFIG_ID)).thenReturn(fileCapability(true, false));

        assertThatThrownBy(() -> service.download(TEST_ARTIFACT_ID, TEST_USER_ID, OutputStream.nullOutputStream()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void cleanupExpiresOnlyAfterExistingFileDeleteSucceeds() throws Exception {
        AgentArtifactDO artifact = artifact(ArtifactStatus.READY);
        artifact.setFileId(TEST_FILE_ID);
        artifact.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(artifactMapper.selectExpiredReady(any(LocalDateTime.class), eq(50))).thenReturn(List.of(artifact));

        assertThat(service.cleanupExpired()).isEqualTo(1);
        assertThat(artifact.getArtifactStatus()).isEqualTo(ArtifactStatus.EXPIRED.name());

        AgentArtifactDO failedDelete = artifact(ArtifactStatus.READY);
        failedDelete.setFileId(TEST_FILE_ID + 1);
        when(artifactMapper.selectExpiredReady(any(LocalDateTime.class), eq(50))).thenReturn(List.of(failedDelete));
        doThrow(new IllegalStateException("delete failed")).when(fileApi).deleteFileIfExists(TEST_FILE_ID + 1);
        assertThat(service.cleanupExpired()).isZero();
        assertThat(failedDelete.getArtifactStatus()).isEqualTo(ArtifactStatus.READY.name());
    }

    @Test
    void cleanupUsesIdempotentFileDeleteAndConvergesAfterArtifactUpdateFailure() throws Exception {
        AgentArtifactDO artifact = artifact(ArtifactStatus.READY);
        artifact.setFileId(TEST_FILE_ID);
        artifact.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(artifactMapper.selectExpiredReady(any(LocalDateTime.class), eq(50))).thenReturn(List.of(artifact));
        doThrow(new IllegalStateException("db failed"))
                .doAnswer(invocation -> {
                    artifactStore.set(invocation.getArgument(0));
                    return 1;
                })
                .when(artifactMapper).updateById(artifact);

        assertThat(service.cleanupExpired()).isZero();
        artifact.setArtifactStatus(ArtifactStatus.READY.name());
        assertThat(service.cleanupExpired()).isEqualTo(1);

        verify(fileApi, times(2)).deleteFileIfExists(TEST_FILE_ID);
        assertThat(artifact.getArtifactStatus()).isEqualTo(ArtifactStatus.EXPIRED.name());
    }

    @Test
    void cleanupUsesTenantScopedArtifactLockAndSkipsWhenLockedByAnotherNode() throws Exception {
        AgentArtifactDO artifact = artifact(ArtifactStatus.READY);
        artifact.setFileId(TEST_FILE_ID);
        when(artifactMapper.selectExpiredReady(any(LocalDateTime.class), eq(50))).thenReturn(List.of(artifact));
        when(lock.tryLock()).thenReturn(false);

        assertThat(service.cleanupExpired()).isZero();

        verify(fileApi, never()).deleteFileIfExists(TEST_FILE_ID);
        verify(redissonClient).getLock(AgentCoordinationKeys.artifactCleanupLock(TEST_TENANT_ID, TEST_ARTIFACT_ID));
    }

    @Test
    void cleanupJobUsesTenantJobAndPropertiesInterval() throws Exception {
        AgentArtifactCleanupJob job = new AgentArtifactCleanupJob(service, artifactProperties);

        assertThat(job.cleanupIntervalMillis()).isEqualTo(Duration.ofSeconds(45).toMillis());
        assertThat(AgentArtifactCleanupJob.class.getMethod("cleanupExpired").isAnnotationPresent(TenantJob.class))
                .isTrue();
        assertThat(AgentArtifactCleanupJob.class.getMethod("cleanupExpired").isAnnotationPresent(Scheduled.class))
                .isTrue();
    }

    @Test
    void pageResolvesBusinessFiltersAndNeverReturnsStorageDetails() {
        AgentArtifactPageReqVO reqVO = new AgentArtifactPageReqVO();
        reqVO.setSessionId(TEST_SESSION_ID);
        reqVO.setProjectId(TEST_PROJECT_ID);
        when(sessionMapper.selectBySessionId(TEST_SESSION_ID)).thenReturn(session(AgentSessionDbStatus.IDLE));
        when(projectMapper.selectByProjectId(TEST_PROJECT_ID)).thenReturn(project());
        when(artifactMapper.selectPage(eq(reqVO), eq(TEST_USER_ID), eq(TEST_SESSION_DB_ID), eq(TEST_PROJECT_DB_ID)))
                .thenReturn(new PageResult<>(List.of(artifact(ArtifactStatus.READY)), 1L));

        PageResult<?> page = service.getArtifactPage(reqVO, TEST_USER_ID);

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList().getFirst()).extracting("artifactId", "status")
                .containsExactly(TEST_ARTIFACT_ID, ArtifactStatus.READY.name());
        List<String> fieldNames = Arrays.stream(page.getList().getFirst().getClass().getDeclaredFields())
                .map(field -> field.getName().toLowerCase())
                .toList();
        assertThat(fieldNames).noneMatch(name -> name.contains("bucket") || name.contains("storage")
                || name.contains("objectkey"));
    }

    private void stubArtifactMapperStore() {
        doAnswer(invocation -> {
            AgentArtifactDO artifact = invocation.getArgument(0);
            artifact.setId(100L);
            artifactStore.set(artifact);
            return 1;
        }).when(artifactMapper).insert(any(AgentArtifactDO.class));
        doAnswer(invocation -> {
            artifactStore.set(invocation.getArgument(0));
            return 1;
        }).when(artifactMapper).updateById(any(AgentArtifactDO.class));
        when(artifactMapper.selectByArtifactId(anyString())).thenAnswer(invocation -> {
            AgentArtifactDO artifact = artifactStore.get();
            return artifact != null && invocation.getArgument(0).equals(artifact.getArtifactId()) ? artifact : null;
        });
    }

    private void stubCommandMapperStore() {
        doAnswer(invocation -> {
            AgentCommandDO command = invocation.getArgument(0);
            command.setId(TEST_TRANSFER_COMMAND_DB_ID);
            transferCommandStore.set(command);
            return 1;
        }).when(commandMapper).insert(any(AgentCommandDO.class));
        doAnswer(invocation -> {
            AgentCommandDO command = invocation.getArgument(0);
            if (TEST_TRANSFER_COMMAND_DB_ID.equals(command.getId())) {
                transferCommandStore.set(command);
            }
            return 1;
        }).when(commandMapper).updateById(any(AgentCommandDO.class));
        when(commandMapper.selectById(TEST_SOURCE_COMMAND_DB_ID)).thenReturn(sourceCommand());
        when(commandMapper.selectById(TEST_TRANSFER_COMMAND_DB_ID)).thenAnswer(invocation -> transferCommandStore.get());
        when(commandMapper.selectByCommandId(TEST_TRANSFER_COMMAND_ID)).thenAnswer(invocation -> transferCommandStore.get());
    }

    private void stubGraph(AgentSessionDbStatus sessionStatus, FileChangeType changeType, boolean redacted) {
        when(fileChangeMapper.selectByFileChangeId(TEST_FILE_CHANGE_ID)).thenReturn(fileChange(changeType, redacted));
        when(changeSetMapper.selectById(TEST_CHANGE_SET_DB_ID)).thenReturn(changeSet());
        when(sessionMapper.selectById(TEST_SESSION_DB_ID)).thenReturn(session(sessionStatus));
        when(deviceMapper.selectById(TEST_DEVICE_DB_ID)).thenReturn(device());
        when(projectMapper.selectById(TEST_PROJECT_DB_ID)).thenReturn(project());
        when(routeLookupService.getRoute(TEST_DEVICE_ID)).thenReturn(route());
    }

    private void stubCredential() {
        when(credentialAuthService.authenticate(TEST_TENANT_ID, "credential-id", "secret"))
                .thenReturn(new DeviceCredentialIdentity(TEST_TENANT_ID, TEST_USER_ID, device()));
    }

    private void stubFileCreateConsumesStream() throws Exception {
        when(fileApi.createFile(any(FileUploadReqDTO.class))).thenAnswer(invocation -> {
            FileUploadReqDTO reqDTO = invocation.getArgument(0);
            reqDTO.getInputStream().transferTo(OutputStream.nullOutputStream());
            return fileResp();
        });
    }

    private FileClientCapabilityRespDTO fileCapability(boolean streamingUpload, boolean streamingDownload) {
        FileClientCapabilityRespDTO capability = new FileClientCapabilityRespDTO();
        capability.setConfigId(TEST_FILE_CONFIG_ID);
        capability.setStreamingUpload(streamingUpload);
        capability.setStreamingDownload(streamingDownload);
        return capability;
    }

    private AgentArtifactRequestFileReqVO request(String clientRequestId) {
        AgentArtifactRequestFileReqVO reqVO = new AgentArtifactRequestFileReqVO();
        reqVO.setFileChangeId(TEST_FILE_CHANGE_ID);
        reqVO.setClientRequestId(clientRequestId);
        return reqVO;
    }

    private AgentArtifactPrepareUploadReqVO prepareReq(String sha256) {
        AgentArtifactPrepareUploadReqVO reqVO = new AgentArtifactPrepareUploadReqVO();
        reqVO.setArtifactId(TEST_ARTIFACT_ID);
        reqVO.setFileSize(TEST_CONTENT.length);
        reqVO.setSha256(sha256);
        reqVO.setContentType(TEST_CONTENT_TYPE);
        reqVO.setSourceLastModifiedTime(LocalDateTime.now());
        return reqVO;
    }

    private AgentArtifactDO artifact() {
        return artifact(ArtifactStatus.ROUTING);
    }

    private AgentArtifactDO artifact(ArtifactStatus status) {
        AgentArtifactDO artifact = new AgentArtifactDO();
        artifact.setId(100L);
        artifact.setTenantId(TEST_TENANT_ID);
        artifact.setArtifactId(TEST_ARTIFACT_ID);
        artifact.setArtifactSourceType(ArtifactSourceType.CHANGE_SET_FILE.name());
        artifact.setArtifactStatus(status.name());
        artifact.setSessionId(TEST_SESSION_DB_ID);
        artifact.setSourceCommandId(TEST_SOURCE_COMMAND_DB_ID);
        artifact.setTransferCommandId(TEST_TRANSFER_COMMAND_DB_ID);
        artifact.setChangeSetId(TEST_CHANGE_SET_DB_ID);
        artifact.setFileChangeId(TEST_FILE_CHANGE_DB_ID);
        artifact.setDeviceId(TEST_DEVICE_DB_ID);
        artifact.setProjectId(TEST_PROJECT_DB_ID);
        artifact.setOwnerUserId(TEST_USER_ID);
        artifact.setRelativePath(TEST_RELATIVE_PATH);
        artifact.setFileName("App.java");
        artifact.setContentType(TEST_CONTENT_TYPE);
        artifact.setFileSize((long) TEST_CONTENT.length);
        artifact.setSha256(sha256(TEST_CONTENT));
        artifact.setRequestedTime(LocalDateTime.now());
        return artifact;
    }

    private AgentFileChangeDO fileChange(FileChangeType changeType, boolean redacted) {
        AgentFileChangeDO fileChange = new AgentFileChangeDO();
        fileChange.setId(TEST_FILE_CHANGE_DB_ID);
        fileChange.setTenantId(TEST_TENANT_ID);
        fileChange.setFileChangeId(TEST_FILE_CHANGE_ID);
        fileChange.setChangeSetId(TEST_CHANGE_SET_DB_ID);
        fileChange.setSessionId(TEST_SESSION_DB_ID);
        fileChange.setCommandId(TEST_SOURCE_COMMAND_DB_ID);
        fileChange.setRelativePath(TEST_RELATIVE_PATH);
        fileChange.setChangeType(changeType.name());
        fileChange.setRedacted(redacted);
        return fileChange;
    }

    private AgentChangeSetDO changeSet() {
        AgentChangeSetDO changeSet = new AgentChangeSetDO();
        changeSet.setId(TEST_CHANGE_SET_DB_ID);
        changeSet.setTenantId(TEST_TENANT_ID);
        changeSet.setChangeSetId(TEST_CHANGE_SET_ID);
        changeSet.setSessionId(TEST_SESSION_DB_ID);
        changeSet.setCommandId(TEST_SOURCE_COMMAND_DB_ID);
        changeSet.setDeviceId(TEST_DEVICE_DB_ID);
        changeSet.setProjectId(TEST_PROJECT_DB_ID);
        changeSet.setOwnerUserId(TEST_USER_ID);
        return changeSet;
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

    private AgentCommandDO sourceCommand() {
        AgentCommandDO command = new AgentCommandDO();
        command.setId(TEST_SOURCE_COMMAND_DB_ID);
        command.setTenantId(TEST_TENANT_ID);
        command.setCommandId(TEST_SOURCE_COMMAND_ID);
        command.setSessionId(TEST_SESSION_DB_ID);
        command.setDeviceId(TEST_DEVICE_DB_ID);
        command.setProjectId(TEST_PROJECT_DB_ID);
        command.setOwnerUserId(TEST_USER_ID);
        command.setCommandType(CommandType.PROMPT.name());
        command.setCommandStatus(AgentCommandDbStatus.SUCCEEDED.name());
        return command;
    }

    private AgentCommandDO transferCommand(AgentCommandDbStatus status) {
        AgentCommandDO command = new AgentCommandDO();
        command.setId(TEST_TRANSFER_COMMAND_DB_ID);
        command.setTenantId(TEST_TENANT_ID);
        command.setCommandId(TEST_TRANSFER_COMMAND_ID);
        command.setSessionId(TEST_SESSION_DB_ID);
        command.setDeviceId(TEST_DEVICE_DB_ID);
        command.setProjectId(TEST_PROJECT_DB_ID);
        command.setOwnerUserId(TEST_USER_ID);
        command.setCommandType(CommandType.FETCH_ARTIFACT.name());
        command.setCommandStatus(status.name());
        return command;
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

    private AgentProjectDO project() {
        AgentProjectDO project = new AgentProjectDO();
        project.setId(TEST_PROJECT_DB_ID);
        project.setTenantId(TEST_TENANT_ID);
        project.setProjectId(TEST_PROJECT_ID);
        project.setDeviceId(TEST_DEVICE_DB_ID);
        project.setOwnerUserId(TEST_USER_ID);
        project.setAgentType(AgentType.CODEX.name());
        return project;
    }

    private DeviceRoutePayload route() {
        return new DeviceRoutePayload(TEST_RELAY_NODE_ID, TEST_CONNECTION_ID, TEST_TENANT_ID, TEST_USER_ID,
                TEST_DEVICE_ID, Instant.now(), Instant.now());
    }

    private FileRespDTO fileResp() {
        FileRespDTO file = new FileRespDTO();
        file.setId(TEST_FILE_ID);
        file.setConfigId(TEST_FILE_CONFIG_ID);
        file.setName("App.java");
        file.setPath("agent/artifact/App.java");
        file.setType(TEST_CONTENT_TYPE);
        file.setSize((long) TEST_CONTENT.length);
        return file;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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
