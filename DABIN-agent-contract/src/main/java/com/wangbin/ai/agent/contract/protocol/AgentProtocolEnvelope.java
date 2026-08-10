package com.wangbin.ai.agent.contract.protocol;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentProtocolEnvelope<T>(
        String envelopeId,
        String traceId,
        String protocol,
        String protocolVersion,
        Instant timestamp,
        T body,
        Map<String, Object> extensions
) {

    public AgentProtocolEnvelope {
        envelopeId = envelopeId == null || envelopeId.isBlank() ? UUID.randomUUID().toString() : envelopeId;
        protocol = protocol == null ? AgentProtocol.NAME : protocol;
        protocolVersion = protocolVersion == null ? AgentProtocol.VERSION : protocolVersion;
        timestamp = timestamp == null ? Instant.now() : timestamp;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
