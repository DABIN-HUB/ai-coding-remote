package com.wangbin.ai.agent.contract.protocol;

/**
 * HTTP headers used by AI Coding Remote daemon/control-plane protocol.
 * This class stays in contract so daemon and control plane cannot drift.
 */
public final class AgentHttpHeaders {

    /**
     * Must match RuoYi tenant header name used by WebFrameworkUtils.
     */
    public static final String TENANT_ID = "tenant-id";
    public static final String CREDENTIAL_ID = "X-Agent-Credential-Id";
    public static final String CREDENTIAL_SECRET = "X-Agent-Credential-Secret";
    public static final String ARTIFACT_UPLOAD_TICKET = "X-Agent-Artifact-Upload-Ticket";

    private AgentHttpHeaders() {
    }
}
