package com.wangbin.ai.module.agent.service.device;

import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.DevicePresencePayload;
import com.wangbin.ai.agent.contract.coordination.PairingCodePayload;
import com.wangbin.ai.agent.contract.coordination.RelayTicketPayload;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.module.agent.controller.admin.device.vo.*;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceCredentialDO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceCredentialMapper;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceMapper;
import com.wangbin.ai.module.agent.enums.CredentialStatus;
import com.wangbin.ai.module.agent.enums.DeviceStatus;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import com.wangbin.ai.module.agent.service.pairing.PairingCodeService;
import com.wangbin.ai.module.agent.service.relay.RelayTicketService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.*;

@Service
@RequiredArgsConstructor
public class AgentDeviceServiceImpl implements AgentDeviceService {

    private static final String DEVICE_ID_PREFIX = "dev_";
    private static final String CREDENTIAL_ID_PREFIX = "cred_";

    private final AgentDeviceMapper deviceMapper;
    private final AgentDeviceCredentialMapper credentialMapper;
    private final PairingCodeService pairingCodeService;
    private final RelayTicketService relayTicketService;
    private final DevicePresenceService presenceService;
    private final PasswordEncoder passwordEncoder;
    private final RedissonClient redissonClient;
    private final AgentControlPlaneProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public AgentPairingCodeRespVO createPairingCode(Long tenantId, Long userId) {
        if (tenantId == null) {
            throw exception(TENANT_ID_REQUIRED);
        }
        PairingCodePayload payload = pairingCodeService.createPairingCode(tenantId, userId);
        return new AgentPairingCodeRespVO(payload.pairingCode(), payload.expireAt());
    }

    @Override
    public PageResult<AgentDeviceRespVO> getDevicePage(AgentDevicePageReqVO reqVO, Long userId) {
        PageResult<AgentDeviceDO> pageResult = deviceMapper.selectPage(reqVO, userId);
        return new PageResult<>(pageResult.getList().stream().map(this::toRespVO).toList(), pageResult.getTotal());
    }

    @Override
    public AgentDeviceRespVO getDevice(Long id, Long userId) {
        AgentDeviceDO device = deviceMapper.selectById(id);
        checkOwner(device, userId);
        return toRespVO(device);
    }

    @Override
    public AgentDevicePairRespVO pairDevice(AgentDevicePairReqVO reqVO) {
        PairingCodePayload payload = pairingCodeService.consumePairingCode(reqVO.getPairingCode());
        Long oldTenantId = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(payload.tenantId());
        RLock lock = redissonClient.getLock(AgentCoordinationKeys.pairingLock(payload.tenantId(), payload.userId(),
                reqVO.getInstallationId()));
        boolean locked = false;
        try {
            locked = lock.tryLock(properties.getPairingLockWaitTime().toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                throw exception(PAIRING_CONCURRENT_CONFLICT);
            }
            return Objects.requireNonNull(transactionTemplate.execute(status -> pairDeviceUnderLock(payload, reqVO)));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw exception(PAIRING_CONCURRENT_CONFLICT);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            if (oldTenantId == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(oldTenantId);
            }
        }
    }

    /**
     * The transaction is opened only after the distributed lock is acquired.
     * Without a unique installation index, the lock must cover the database commit
     * so the next pairing request can observe the already-created device.
     */
    private AgentDevicePairRespVO pairDeviceUnderLock(PairingCodePayload payload, AgentDevicePairReqVO reqVO) {
        AgentDeviceDO device = findReusableDevice(payload.userId(), reqVO.getInstallationId());
        if (device == null) {
            device = createDevice(payload, reqVO);
            deviceMapper.insert(device);
        } else {
            updateDeviceMetadata(device, reqVO);
            deviceMapper.updateById(device);
            revokeActiveCredentials(device.getId(), payload.userId());
        }
        String credentialSecret = randomSecret();
        AgentDeviceCredentialDO credential = createCredential(device, credentialSecret, payload.userId());
        credentialMapper.insert(credential);

        AgentDevicePairRespVO respVO = new AgentDevicePairRespVO();
        respVO.setTenantId(payload.tenantId());
        respVO.setDeviceId(device.getDeviceId());
        respVO.setCredentialId(credential.getCredentialId());
        respVO.setCredentialSecret(credentialSecret);
        return respVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentDeviceRelayTicketRespVO createDeviceRelayTicket(Long tenantId, String credentialId,
                                                                String credentialSecret) {
        if (tenantId == null) {
            throw exception(TENANT_ID_REQUIRED);
        }
        Long oldTenantId = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            AgentDeviceCredentialDO credential = credentialMapper.selectByCredentialId(credentialId);
            if (credential == null || !passwordEncoder.matches(credentialSecret, credential.getSecretHash())) {
                throw exception(DEVICE_CREDENTIAL_INVALID);
            }
            if (CredentialStatus.REVOKED.name().equals(credential.getCredentialStatus())) {
                throw exception(DEVICE_CREDENTIAL_REVOKED);
            }
            if (credential.getExpireTime() != null && credential.getExpireTime().isBefore(LocalDateTime.now())) {
                throw exception(DEVICE_CREDENTIAL_EXPIRED);
            }
            AgentDeviceDO device = deviceMapper.selectById(credential.getDeviceId());
            if (device == null) {
                throw exception(DEVICE_NOT_EXISTS);
            }
            if (!device.isActive()) {
                throw exception(DEVICE_DISABLED);
            }
            credential.setLastUsedTime(LocalDateTime.now());
            credentialMapper.updateById(credential);
            RelayTicketPayload ticket = relayTicketService.createDeviceTicket(tenantId, device.getOwnerUserId(),
                    device.getDeviceId());
            return new AgentDeviceRelayTicketRespVO(ticket.ticket(), ticket.expireAt());
        } finally {
            if (oldTenantId == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(oldTenantId);
            }
        }
    }

    private AgentDeviceDO findReusableDevice(Long ownerUserId, String installationId) {
        List<AgentDeviceDO> devices = deviceMapper.selectListByInstallation(ownerUserId, installationId);
        return devices.isEmpty() ? null : devices.getFirst();
    }

    private AgentDeviceDO createDevice(PairingCodePayload payload, AgentDevicePairReqVO reqVO) {
        AgentDeviceDO device = new AgentDeviceDO();
        device.setTenantId(payload.tenantId());
        device.setDeviceId(DEVICE_ID_PREFIX + randomSecret().substring(0, 22));
        device.setInstallationId(reqVO.getInstallationId());
        device.setOwnerUserId(payload.userId());
        device.setDeviceStatus(DeviceStatus.ACTIVE.name());
        device.setCreator(String.valueOf(payload.userId()));
        device.setUpdater(String.valueOf(payload.userId()));
        updateDeviceMetadata(device, reqVO);
        return device;
    }

    private void updateDeviceMetadata(AgentDeviceDO device, AgentDevicePairReqVO reqVO) {
        device.setDeviceName(blankToEmpty(reqVO.getDeviceName()));
        device.setHostname(blankToEmpty(reqVO.getHostname()));
        device.setOsName(blankToEmpty(reqVO.getOsName()));
        device.setOsVersion(blankToEmpty(reqVO.getOsVersion()));
        device.setOsArch(blankToEmpty(reqVO.getOsArch()));
        device.setDaemonVersion(blankToEmpty(reqVO.getDaemonVersion()));
    }

    private AgentDeviceCredentialDO createCredential(AgentDeviceDO device, String credentialSecret, Long operatorUserId) {
        AgentDeviceCredentialDO credential = new AgentDeviceCredentialDO();
        credential.setTenantId(device.getTenantId());
        credential.setDeviceId(device.getId());
        credential.setCredentialId(CREDENTIAL_ID_PREFIX + randomSecret().substring(0, 22));
        credential.setSecretHash(passwordEncoder.encode(credentialSecret));
        credential.setCredentialStatus(CredentialStatus.ACTIVE.name());
        credential.setCreator(String.valueOf(operatorUserId));
        credential.setUpdater(String.valueOf(operatorUserId));
        return credential;
    }

    private void revokeActiveCredentials(Long deviceId, Long operatorUserId) {
        for (AgentDeviceCredentialDO credential : credentialMapper.selectActiveListByDeviceId(deviceId)) {
            credential.setCredentialStatus(CredentialStatus.REVOKED.name());
            credential.setRevokedTime(LocalDateTime.now());
            credential.setUpdater(String.valueOf(operatorUserId));
            credentialMapper.updateById(credential);
        }
    }

    private void checkOwner(AgentDeviceDO device, Long userId) {
        if (device == null) {
            throw exception(DEVICE_NOT_EXISTS);
        }
        if (!device.getOwnerUserId().equals(userId)) {
            throw exception(DEVICE_ACCESS_DENIED);
        }
    }

    private AgentDeviceRespVO toRespVO(AgentDeviceDO device) {
        AgentDeviceRespVO respVO = new AgentDeviceRespVO();
        respVO.setId(device.getId());
        respVO.setDeviceId(device.getDeviceId());
        respVO.setInstallationId(device.getInstallationId());
        respVO.setOwnerUserId(device.getOwnerUserId());
        respVO.setDeviceName(device.getDeviceName());
        respVO.setHostname(device.getHostname());
        respVO.setOsName(device.getOsName());
        respVO.setOsVersion(device.getOsVersion());
        respVO.setOsArch(device.getOsArch());
        respVO.setDaemonVersion(device.getDaemonVersion());
        respVO.setDeviceStatus(device.getDeviceStatus());
        respVO.setRemark(device.getRemark());
        respVO.setCreateTime(device.getCreateTime());
        DevicePresencePayload presence = presenceService.getPresence(device.getDeviceId());
        respVO.setOnline(presence != null);
        if (presence != null) {
            respVO.setLastSeenAt(presence.lastSeenAt());
            respVO.setRelayNodeId(presence.relayNodeId());
        }
        return respVO;
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
