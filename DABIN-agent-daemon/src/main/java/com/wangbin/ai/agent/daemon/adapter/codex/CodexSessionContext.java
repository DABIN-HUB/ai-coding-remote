package com.wangbin.ai.agent.daemon.adapter.codex;

import com.wangbin.ai.agent.contract.enums.AgentType;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the adapter-local mapping between the platform session and the Codex
 * native thread. The sequence is intentionally scoped to this session.
 */
public class CodexSessionContext {

    private final String platformSessionId;
    private final String nativeSessionId;
    private final Long tenantId;
    private final Long userId;
    private final String deviceId;
    private final String projectId;
    private final String workspacePath;
    private final AgentType agentType;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<String> activePlatformCommandId = new AtomicReference<>();

    public CodexSessionContext(String platformSessionId, String nativeSessionId, Long tenantId, Long userId,
                               String deviceId, String projectId, String workspacePath, AgentType agentType) {
        this.platformSessionId = platformSessionId;
        this.nativeSessionId = nativeSessionId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.deviceId = deviceId;
        this.projectId = projectId;
        this.workspacePath = workspacePath;
        this.agentType = agentType == null ? AgentType.UNKNOWN : agentType;
    }

    public String platformSessionId() {
        return platformSessionId;
    }

    public String nativeSessionId() {
        return nativeSessionId;
    }

    public Long tenantId() {
        return tenantId;
    }

    public Long userId() {
        return userId;
    }

    public String deviceId() {
        return deviceId;
    }

    public String projectId() {
        return projectId;
    }

    public String workspacePath() {
        return workspacePath;
    }

    public AgentType agentType() {
        return agentType;
    }

    public long nextSeq() {
        return sequence.incrementAndGet();
    }

    public boolean beginPlatformCommand(String commandId) {
        return activePlatformCommandId.compareAndSet(null, commandId);
    }

    public String activePlatformCommandId() {
        return activePlatformCommandId.get();
    }

    public void clearPlatformCommand(String commandId) {
        activePlatformCommandId.compareAndSet(commandId, null);
    }

}
