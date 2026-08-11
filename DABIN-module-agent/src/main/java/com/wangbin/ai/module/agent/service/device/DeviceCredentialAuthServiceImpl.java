package com.wangbin.ai.module.agent.service.device;

import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceCredentialDO;
import com.wangbin.ai.module.agent.dal.dataobject.device.AgentDeviceDO;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceCredentialMapper;
import com.wangbin.ai.module.agent.dal.mysql.device.AgentDeviceMapper;
import com.wangbin.ai.module.agent.enums.CredentialStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.*;

@Service
@RequiredArgsConstructor
public class DeviceCredentialAuthServiceImpl implements DeviceCredentialAuthService {

    private final AgentDeviceCredentialMapper credentialMapper;
    private final AgentDeviceMapper deviceMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public DeviceCredentialIdentity authenticate(Long tenantId, String credentialId, String credentialSecret) {
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
            return new DeviceCredentialIdentity(tenantId, device.getOwnerUserId(), device);
        } finally {
            if (oldTenantId == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(oldTenantId);
            }
        }
    }
}
