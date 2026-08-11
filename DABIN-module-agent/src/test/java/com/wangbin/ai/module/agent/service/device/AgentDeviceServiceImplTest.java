package com.wangbin.ai.module.agent.service.device;

import com.wangbin.ai.agent.contract.coordination.PairingCodePayload;
import com.wangbin.ai.agent.contract.coordination.RelayTicketPayload;
import com.wangbin.ai.agent.contract.coordination.RelaySubjectType;
import com.wangbin.ai.module.agent.controller.admin.device.vo.AgentDevicePairReqVO;
import com.wangbin.ai.module.agent.controller.admin.device.vo.AgentDevicePairRespVO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceCredentialDO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceCredentialMapper;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceMapper;
import com.wangbin.ai.module.agent.enums.CredentialStatus;
import com.wangbin.ai.module.agent.enums.DeviceStatus;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import com.wangbin.ai.module.agent.service.pairing.PairingCodeService;
import com.wangbin.ai.module.agent.service.relay.RelayTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDeviceServiceImplTest {

    private final AgentDeviceMapper deviceMapper = mock(AgentDeviceMapper.class);
    private final AgentDeviceCredentialMapper credentialMapper = mock(AgentDeviceCredentialMapper.class);
    private final PairingCodeService pairingCodeService = mock(PairingCodeService.class);
    private final RelayTicketService relayTicketService = mock(RelayTicketService.class);
    private final DevicePresenceService presenceService = mock(DevicePresenceService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final AgentControlPlaneProperties properties = new AgentControlPlaneProperties();
    private final AgentDeviceServiceImpl service = new AgentDeviceServiceImpl(deviceMapper, credentialMapper,
            pairingCodeService, relayTicketService, presenceService, passwordEncoder, redissonClient, properties);

    @BeforeEach
    void setUp() throws InterruptedException {
        when(passwordEncoder.encode(any())).thenReturn("encoded-secret");
        when(redissonClient.getLock(any(String.class))).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void pairCreatesDeviceAndCredentialFromServerSidePairingPayload() {
        when(pairingCodeService.consumePairingCode("ABCD-EFGH")).thenReturn(pairingPayload());
        when(deviceMapper.selectListByInstallation(11L, "install-1")).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentDeviceDO device = invocation.getArgument(0);
            device.setId(100L);
            return 1;
        }).when(deviceMapper).insert(any(AgentDeviceDO.class));

        AgentDevicePairRespVO response = service.pairDevice(pairReq());

        assertThat(response.getTenantId()).isEqualTo(1L);
        assertThat(response.getDeviceId()).startsWith("dev_");
        assertThat(response.getCredentialId()).startsWith("cred_");
        assertThat(response.getCredentialSecret()).isNotBlank();
        verify(credentialMapper).insert(any(AgentDeviceCredentialDO.class));
        verify(lock).unlock();
    }

    @Test
    void repeatedPairReusesDeviceAndRevokesOldActiveCredential() {
        when(pairingCodeService.consumePairingCode("ABCD-EFGH")).thenReturn(pairingPayload());
        AgentDeviceDO existing = existingDevice();
        AgentDeviceCredentialDO oldCredential = new AgentDeviceCredentialDO();
        oldCredential.setId(200L);
        oldCredential.setCredentialStatus(CredentialStatus.ACTIVE.name());
        when(deviceMapper.selectListByInstallation(11L, "install-1")).thenReturn(List.of(existing));
        when(credentialMapper.selectActiveListByDeviceId(100L)).thenReturn(new ArrayList<>(List.of(oldCredential)));

        AgentDevicePairRespVO response = service.pairDevice(pairReq());

        assertThat(response.getDeviceId()).isEqualTo("dev_existing");
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
        when(deviceMapper.selectListByInstallation(11L, "install-1")).thenAnswer(invocation -> {
            AgentDeviceDO device = storedDevice.get();
            return device == null ? List.of() : List.of(device);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentDeviceDO device = invocation.getArgument(0);
            device.setId(100L);
            storedDevice.set(device);
            return 1;
        }).when(deviceMapper).insert(any(AgentDeviceDO.class));
        when(credentialMapper.selectActiveListByDeviceId(100L)).thenReturn(new ArrayList<>());
        ReentrantLock javaLock = new ReentrantLock();
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation ->
                javaLock.tryLock((Long) invocation.getArgument(0), (TimeUnit) invocation.getArgument(2)));
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
    void createRelayTicketAuthenticatesCredentialAndDoesNotTrustClientDeviceId() {
        AgentDeviceCredentialDO credential = new AgentDeviceCredentialDO();
        credential.setId(200L);
        credential.setDeviceId(100L);
        credential.setCredentialStatus(CredentialStatus.ACTIVE.name());
        credential.setSecretHash("hash");
        when(credentialMapper.selectByCredentialId("cred-1")).thenReturn(credential);
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        when(deviceMapper.selectById(100L)).thenReturn(existingDevice());
        RelayTicketPayload ticket = new RelayTicketPayload("ticket-1", RelaySubjectType.DEVICE, 1L,
                11L, "dev_existing", Instant.now(), Instant.now().plusSeconds(60));
        when(relayTicketService.createDeviceTicket(1L, 11L, "dev_existing")).thenReturn(ticket);

        var response = service.createDeviceRelayTicket(1L, "cred-1", "secret");

        assertThat(response.getTicket()).isEqualTo("ticket-1");
        assertThat(credential.getLastUsedTime()).isBeforeOrEqualTo(LocalDateTime.now());
        verify(credentialMapper).updateById(credential);
    }

    private PairingCodePayload pairingPayload() {
        return new PairingCodePayload("ABCD-EFGH", 1L, 11L, Instant.now(), Instant.now().plusSeconds(300));
    }

    private AgentDevicePairReqVO pairReq() {
        AgentDevicePairReqVO reqVO = new AgentDevicePairReqVO();
        reqVO.setPairingCode("ABCD-EFGH");
        reqVO.setInstallationId("install-1");
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
        device.setId(100L);
        device.setTenantId(1L);
        device.setDeviceId("dev_existing");
        device.setInstallationId("install-1");
        device.setOwnerUserId(11L);
        device.setDeviceStatus(DeviceStatus.ACTIVE.name());
        return device;
    }
}
