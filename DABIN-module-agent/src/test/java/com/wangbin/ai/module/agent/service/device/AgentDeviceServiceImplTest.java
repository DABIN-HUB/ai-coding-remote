package com.wangbin.ai.module.agent.service.device;

import com.wangbin.ai.agent.contract.coordination.PairingCodePayload;
import com.wangbin.ai.agent.contract.coordination.RelayTicketPayload;
import com.wangbin.ai.agent.contract.coordination.RelaySubjectType;
import com.wangbin.ai.agent.contract.runtime.AgentRuntimeTypes;
import com.wangbin.ai.module.agent.controller.admin.device.vo.AgentDevicePairReqVO;
import com.wangbin.ai.module.agent.controller.admin.device.vo.AgentDevicePairRespVO;
import com.wangbin.ai.module.agent.controller.admin.device.vo.AgentDeviceRespVO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceCredentialDO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.dataobject.runtime.AgentRuntimeDO;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceCredentialMapper;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceMapper;
import com.wangbin.ai.module.agent.dal.mysql.runtime.AgentRuntimeMapper;
import com.wangbin.ai.module.agent.enums.CredentialStatus;
import com.wangbin.ai.module.agent.enums.DeviceStatus;
import com.wangbin.ai.module.agent.enums.RuntimeStatus;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import com.wangbin.ai.module.agent.service.pairing.PairingCodeService;
import com.wangbin.ai.module.agent.service.relay.RelayTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDeviceServiceImplTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final Long TEST_DEVICE_DB_ID = 100L;
    private static final Long TEST_CREDENTIAL_DB_ID = 200L;
    private static final String PAIRING_CODE = "ABCD-EFGH";
    private static final String PAIRING_CODE_FIRST = "AAAA-BBBB";
    private static final String PAIRING_CODE_SECOND = "CCCC-DDDD";
    private static final String TEST_INSTALLATION_ID = "install-1";
    private static final String EXISTING_DEVICE_ID = "dev_existing";
    private static final String DEVICE_ID_PREFIX = "dev_";
    private static final String CREDENTIAL_ID_PREFIX = "cred_";
    private static final String TEST_CREDENTIAL_ID = "cred-1";
    private static final String TEST_CREDENTIAL_SECRET = "secret";
    private static final String TEST_CREDENTIAL_HASH = "hash";
    private static final String TEST_RELAY_TICKET = "ticket-1";
    private static final long PAIRING_TTL_SECONDS = 300L;
    private static final long RELAY_TICKET_TTL_SECONDS = 60L;

    private final AgentDeviceMapper deviceMapper = mock(AgentDeviceMapper.class);
    private final AgentDeviceCredentialMapper credentialMapper = mock(AgentDeviceCredentialMapper.class);
    private final AgentRuntimeMapper runtimeMapper = mock(AgentRuntimeMapper.class);
    private final PairingCodeService pairingCodeService = mock(PairingCodeService.class);
    private final RelayTicketService relayTicketService = mock(RelayTicketService.class);
    private final DevicePresenceService presenceService = mock(DevicePresenceService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final AgentControlPlaneProperties properties = new AgentControlPlaneProperties();
    private final List<TransactionTestEvent> events = Collections.synchronizedList(new ArrayList<>());
    private AgentDeviceServiceImpl service;

    @BeforeEach
    void setUp() throws InterruptedException {
        service = new AgentDeviceServiceImpl(deviceMapper, credentialMapper, runtimeMapper, pairingCodeService, relayTicketService,
                presenceService, passwordEncoder, redissonClient, properties,
                new RecordingTransactionTemplate(events));
        when(passwordEncoder.encode(any())).thenReturn("encoded-secret");
        when(redissonClient.getLock(any(String.class))).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            events.add(TransactionTestEvent.LOCK_ACQUIRED);
            return true;
        });
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            events.add(TransactionTestEvent.LOCK_RELEASE);
            return null;
        }).when(lock).unlock();
    }

    @Test
    void pairCreatesDeviceAndCredentialFromServerSidePairingPayload() {
        when(pairingCodeService.consumePairingCode(PAIRING_CODE)).thenReturn(pairingPayload());
        when(deviceMapper.selectListByInstallation(TEST_USER_ID, TEST_INSTALLATION_ID)).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentDeviceDO device = invocation.getArgument(0);
            device.setId(TEST_DEVICE_DB_ID);
            return 1;
        }).when(deviceMapper).insert(any(AgentDeviceDO.class));

        AgentDevicePairRespVO response = service.pairDevice(pairReq());

        assertThat(response.getTenantId()).isEqualTo(TEST_TENANT_ID);
        assertThat(response.getDeviceId()).startsWith(DEVICE_ID_PREFIX);
        assertThat(response.getCredentialId()).startsWith(CREDENTIAL_ID_PREFIX);
        assertThat(response.getCredentialSecret()).isNotBlank();
        verify(credentialMapper).insert(any(AgentDeviceCredentialDO.class));
        verify(lock).unlock();
    }

    @Test
    void pairLockCoversTransactionCommitBeforeUnlock() {
        when(pairingCodeService.consumePairingCode(PAIRING_CODE)).thenReturn(pairingPayload());
        when(deviceMapper.selectListByInstallation(TEST_USER_ID, TEST_INSTALLATION_ID)).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            events.add(TransactionTestEvent.DB_OPERATION);
            AgentDeviceDO device = invocation.getArgument(0);
            device.setId(TEST_DEVICE_DB_ID);
            return 1;
        }).when(deviceMapper).insert(any(AgentDeviceDO.class));

        service.pairDevice(pairReq());

        assertThat(events).containsSubsequence(TransactionTestEvent.LOCK_ACQUIRED, TransactionTestEvent.TX_BEGIN,
                TransactionTestEvent.DB_OPERATION, TransactionTestEvent.TX_COMMIT,
                TransactionTestEvent.LOCK_RELEASE);
    }

    @Test
    void pairRollsBackBeforeUnlockWhenDatabaseOperationFails() {
        when(pairingCodeService.consumePairingCode(PAIRING_CODE)).thenReturn(pairingPayload());
        when(deviceMapper.selectListByInstallation(TEST_USER_ID, TEST_INSTALLATION_ID)).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            events.add(TransactionTestEvent.DB_OPERATION);
            throw new IllegalStateException("insert failed");
        }).when(deviceMapper).insert(any(AgentDeviceDO.class));

        assertThatThrownBy(() -> service.pairDevice(pairReq()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(events).containsSubsequence(TransactionTestEvent.LOCK_ACQUIRED, TransactionTestEvent.TX_BEGIN,
                TransactionTestEvent.DB_OPERATION, TransactionTestEvent.TX_ROLLBACK,
                TransactionTestEvent.LOCK_RELEASE);
    }

    @Test
    void repeatedPairReusesDeviceAndRevokesOldActiveCredential() {
        when(pairingCodeService.consumePairingCode(PAIRING_CODE)).thenReturn(pairingPayload());
        AgentDeviceDO existing = existingDevice();
        AgentDeviceCredentialDO oldCredential = new AgentDeviceCredentialDO();
        oldCredential.setId(TEST_CREDENTIAL_DB_ID);
        oldCredential.setCredentialStatus(CredentialStatus.ACTIVE.name());
        when(deviceMapper.selectListByInstallation(TEST_USER_ID, TEST_INSTALLATION_ID)).thenReturn(List.of(existing));
        when(credentialMapper.selectActiveListByDeviceId(TEST_DEVICE_DB_ID))
                .thenReturn(new ArrayList<>(List.of(oldCredential)));

        AgentDevicePairRespVO response = service.pairDevice(pairReq());

        assertThat(response.getDeviceId()).isEqualTo(EXISTING_DEVICE_ID);
        assertThat(oldCredential.getCredentialStatus()).isEqualTo(CredentialStatus.REVOKED.name());
        assertThat(oldCredential.getRevokedTime()).isNotNull();
        verify(deviceMapper).updateById(existing);
        verify(credentialMapper).updateById(oldCredential);
        verify(credentialMapper).insert(any(AgentDeviceCredentialDO.class));
    }

    @Test
    void concurrentPairWithSameInstallationCreatesOnlyOneDevice() throws Exception {
        when(pairingCodeService.consumePairingCode(any())).thenReturn(pairingPayload());
        AtomicReference<AgentDeviceDO> storedDevice = new AtomicReference<>();
        when(deviceMapper.selectListByInstallation(TEST_USER_ID, TEST_INSTALLATION_ID)).thenAnswer(invocation -> {
            AgentDeviceDO device = storedDevice.get();
            return device == null ? List.of() : List.of(device);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentDeviceDO device = invocation.getArgument(0);
            device.setId(TEST_DEVICE_DB_ID);
            storedDevice.set(device);
            return 1;
        }).when(deviceMapper).insert(any(AgentDeviceDO.class));
        when(credentialMapper.selectActiveListByDeviceId(TEST_DEVICE_DB_ID)).thenReturn(new ArrayList<>());
        ReentrantLock javaLock = new ReentrantLock();
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(invocation ->
                javaLock.tryLock((Long) invocation.getArgument(0), (TimeUnit) invocation.getArgument(1)));
        when(lock.isHeldByCurrentThread()).thenAnswer(invocation -> javaLock.isHeldByCurrentThread());
        org.mockito.Mockito.doAnswer(invocation -> {
            javaLock.unlock();
            return null;
        }).when(lock).unlock();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<AgentDevicePairRespVO> first = executor.submit(() -> service.pairDevice(pairReq()));
            Future<AgentDevicePairRespVO> second = executor.submit(() -> service.pairDevice(pairReq()));

            AgentDevicePairRespVO firstResponse = first.get(2, TimeUnit.SECONDS);
            AgentDevicePairRespVO secondResponse = second.get(2, TimeUnit.SECONDS);

            assertThat(firstResponse.getDeviceId()).isEqualTo(secondResponse.getDeviceId());
            verify(deviceMapper, times(1)).insert(any(AgentDeviceDO.class));
            verify(credentialMapper, times(2)).insert(any(AgentDeviceCredentialDO.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentPairSeesFirstDeviceOnlyAfterCommitAndReusesIt() throws Exception {
        AgentDevicePairReqVO firstReq = pairReq(PAIRING_CODE_FIRST);
        AgentDevicePairReqVO secondReq = pairReq(PAIRING_CODE_SECOND);
        when(pairingCodeService.consumePairingCode(PAIRING_CODE_FIRST))
                .thenReturn(pairingPayload(PAIRING_CODE_FIRST));
        when(pairingCodeService.consumePairingCode(PAIRING_CODE_SECOND))
                .thenReturn(pairingPayload(PAIRING_CODE_SECOND));
        AtomicReference<AgentDeviceDO> committedDevice = new AtomicReference<>();
        ThreadLocal<AgentDeviceDO> pendingDevice = new ThreadLocal<>();
        List<AgentDeviceCredentialDO> credentials = Collections.synchronizedList(new ArrayList<>());
        service = new AgentDeviceServiceImpl(deviceMapper, credentialMapper, runtimeMapper, pairingCodeService, relayTicketService,
                presenceService, passwordEncoder, redissonClient, properties,
                new RecordingTransactionTemplate(events, () -> {
                    AgentDeviceDO pending = pendingDevice.get();
                    if (pending != null) {
                        committedDevice.compareAndSet(null, pending);
                    }
                    pendingDevice.remove();
                }, pendingDevice::remove));
        when(deviceMapper.selectListByInstallation(TEST_USER_ID, TEST_INSTALLATION_ID)).thenAnswer(invocation -> {
            AgentDeviceDO device = committedDevice.get();
            return device == null ? List.of() : List.of(device);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentDeviceDO device = invocation.getArgument(0);
            device.setId(TEST_DEVICE_DB_ID);
            pendingDevice.set(device);
            return 1;
        }).when(deviceMapper).insert(any(AgentDeviceDO.class));
        when(credentialMapper.selectActiveListByDeviceId(TEST_DEVICE_DB_ID)).thenAnswer(invocation ->
                credentials.stream()
                        .filter(credential -> CredentialStatus.ACTIVE.name().equals(credential.getCredentialStatus()))
                        .toList());
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentDeviceCredentialDO credential = invocation.getArgument(0);
            credentials.add(credential);
            return 1;
        }).when(credentialMapper).insert(any(AgentDeviceCredentialDO.class));
        ReentrantLock javaLock = new ReentrantLock();
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(invocation ->
                javaLock.tryLock((Long) invocation.getArgument(0), (TimeUnit) invocation.getArgument(1)));
        when(lock.isHeldByCurrentThread()).thenAnswer(invocation -> javaLock.isHeldByCurrentThread());
        org.mockito.Mockito.doAnswer(invocation -> {
            javaLock.unlock();
            return null;
        }).when(lock).unlock();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<AgentDevicePairRespVO> first = executor.submit(() -> service.pairDevice(firstReq));
            Future<AgentDevicePairRespVO> second = executor.submit(() -> service.pairDevice(secondReq));

            AgentDevicePairRespVO firstResponse = first.get(2, TimeUnit.SECONDS);
            AgentDevicePairRespVO secondResponse = second.get(2, TimeUnit.SECONDS);

            assertThat(firstResponse.getDeviceId()).isEqualTo(secondResponse.getDeviceId());
            verify(deviceMapper, times(1)).insert(any(AgentDeviceDO.class));
            assertThat(credentials).hasSize(2);
            assertThat(credentials)
                    .filteredOn(credential -> CredentialStatus.ACTIVE.name().equals(credential.getCredentialStatus()))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createRelayTicketAuthenticatesCredentialAndDoesNotTrustClientDeviceId() {
        AgentDeviceCredentialDO credential = new AgentDeviceCredentialDO();
        credential.setId(TEST_CREDENTIAL_DB_ID);
        credential.setDeviceId(TEST_DEVICE_DB_ID);
        credential.setCredentialStatus(CredentialStatus.ACTIVE.name());
        credential.setSecretHash(TEST_CREDENTIAL_HASH);
        when(credentialMapper.selectByCredentialId(TEST_CREDENTIAL_ID)).thenReturn(credential);
        when(passwordEncoder.matches(TEST_CREDENTIAL_SECRET, TEST_CREDENTIAL_HASH)).thenReturn(true);
        when(deviceMapper.selectById(TEST_DEVICE_DB_ID)).thenReturn(existingDevice());
        RelayTicketPayload ticket = new RelayTicketPayload(TEST_RELAY_TICKET, RelaySubjectType.DEVICE, TEST_TENANT_ID,
                TEST_USER_ID, EXISTING_DEVICE_ID, Instant.now(), Instant.now().plusSeconds(RELAY_TICKET_TTL_SECONDS));
        when(relayTicketService.createDeviceTicket(TEST_TENANT_ID, TEST_USER_ID, EXISTING_DEVICE_ID))
                .thenReturn(ticket);

        var response = service.createDeviceRelayTicket(TEST_TENANT_ID, TEST_CREDENTIAL_ID, TEST_CREDENTIAL_SECRET);

        assertThat(response.getTicket()).isEqualTo(TEST_RELAY_TICKET);
        assertThat(credential.getLastUsedTime()).isBeforeOrEqualTo(LocalDateTime.now());
        verify(credentialMapper).updateById(credential);
    }

    @Test
    void getDeviceShouldExposePresenceAndRuntimeReadinessSeparately() {
        AgentDeviceDO device = existingDevice();
        when(deviceMapper.selectById(TEST_DEVICE_DB_ID)).thenReturn(device);
        when(presenceService.getPresence(EXISTING_DEVICE_ID)).thenReturn(
                new com.wangbin.ai.agent.contract.coordination.DevicePresencePayload("relay-1", "conn-1",
                        TEST_TENANT_ID, TEST_USER_ID, EXISTING_DEVICE_ID, Instant.now()));
        AgentRuntimeDO runtime = new AgentRuntimeDO();
        runtime.setRuntimeStatus(RuntimeStatus.AVAILABLE.name());
        when(runtimeMapper.selectByDeviceAndType(TEST_DEVICE_DB_ID, AgentRuntimeTypes.CODEX_APP_SERVER))
                .thenReturn(runtime);

        AgentDeviceRespVO response = service.getDevice(TEST_DEVICE_DB_ID, TEST_USER_ID);

        assertThat(response.getOnline()).isTrue();
        assertThat(response.getRuntimeStatus()).isEqualTo(RuntimeStatus.AVAILABLE.name());
        assertThat(response.getRuntimeAvailable()).isTrue();
    }

    private PairingCodePayload pairingPayload() {
        return new PairingCodePayload(PAIRING_CODE, TEST_TENANT_ID, TEST_USER_ID, Instant.now(),
                Instant.now().plusSeconds(PAIRING_TTL_SECONDS));
    }

    private PairingCodePayload pairingPayload(String pairingCode) {
        return new PairingCodePayload(pairingCode, TEST_TENANT_ID, TEST_USER_ID, Instant.now(),
                Instant.now().plusSeconds(PAIRING_TTL_SECONDS));
    }

    private AgentDevicePairReqVO pairReq() {
        return pairReq(PAIRING_CODE);
    }

    private AgentDevicePairReqVO pairReq(String pairingCode) {
        AgentDevicePairReqVO reqVO = new AgentDevicePairReqVO();
        reqVO.setPairingCode(pairingCode);
        reqVO.setInstallationId(TEST_INSTALLATION_ID);
        reqVO.setDeviceName("dev box");
        reqVO.setHostname("host");
        reqVO.setOsName("Windows");
        reqVO.setOsVersion("11");
        reqVO.setOsArch("amd64");
        reqVO.setDaemonVersion("0.1.0");
        return reqVO;
    }

    private AgentDeviceDO existingDevice() {
        AgentDeviceDO device = new AgentDeviceDO();
        device.setId(TEST_DEVICE_DB_ID);
        device.setTenantId(TEST_TENANT_ID);
        device.setDeviceId(EXISTING_DEVICE_ID);
        device.setInstallationId(TEST_INSTALLATION_ID);
        device.setOwnerUserId(TEST_USER_ID);
        device.setDeviceStatus(DeviceStatus.ACTIVE.name());
        return device;
    }

    private enum TransactionTestEvent {
        LOCK_ACQUIRED,
        TX_BEGIN,
        DB_OPERATION,
        TX_COMMIT,
        TX_ROLLBACK,
        LOCK_RELEASE
    }

    private static final class RecordingTransactionTemplate extends TransactionTemplate {

        private final List<TransactionTestEvent> events;
        private final Runnable afterCommit;
        private final Runnable afterRollback;

        private RecordingTransactionTemplate(List<TransactionTestEvent> events) {
            this(events, () -> {
            }, () -> {
            });
        }

        private RecordingTransactionTemplate(List<TransactionTestEvent> events, Runnable afterCommit,
                                             Runnable afterRollback) {
            this.events = events;
            this.afterCommit = afterCommit;
            this.afterRollback = afterRollback;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            events.add(TransactionTestEvent.TX_BEGIN);
            try {
                T result = action.doInTransaction(new SimpleTransactionStatus());
                events.add(TransactionTestEvent.TX_COMMIT);
                afterCommit.run();
                return result;
            } catch (RuntimeException | Error ex) {
                events.add(TransactionTestEvent.TX_ROLLBACK);
                afterRollback.run();
                throw ex;
            }
        }
    }
}
