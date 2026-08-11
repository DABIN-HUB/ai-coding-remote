package com.wangbin.ai.module.agent.service.device;

public interface DeviceCredentialAuthService {

    DeviceCredentialIdentity authenticate(Long tenantId, String credentialId, String credentialSecret);
}
