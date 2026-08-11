package com.wangbin.ai.agent.daemon.state;

import java.time.Instant;

public final class DeviceCredentialState {

    private Long tenantId;
    private String deviceId;
    private String credentialId;
    private String credentialSecret;
    private Instant pairedAt;
    private String controlPlaneUrl;
    private String relayUrl;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(String credentialId) {
        this.credentialId = credentialId;
    }

    public String getCredentialSecret() {
        return credentialSecret;
    }

    public void setCredentialSecret(String credentialSecret) {
        this.credentialSecret = credentialSecret;
    }

    public Instant getPairedAt() {
        return pairedAt;
    }

    public void setPairedAt(Instant pairedAt) {
        this.pairedAt = pairedAt;
    }

    public String getControlPlaneUrl() {
        return controlPlaneUrl;
    }

    public void setControlPlaneUrl(String controlPlaneUrl) {
        this.controlPlaneUrl = controlPlaneUrl;
    }

    public String getRelayUrl() {
        return relayUrl;
    }

    public void setRelayUrl(String relayUrl) {
        this.relayUrl = relayUrl;
    }

    @Override
    public String toString() {
        return "DeviceCredentialState{tenantId=%s, deviceId='%s', credentialId='%s', pairedAt=%s, controlPlaneUrl='%s', relayUrl='%s'}"
                .formatted(tenantId, deviceId, credentialId, pairedAt, controlPlaneUrl, relayUrl);
    }
}
