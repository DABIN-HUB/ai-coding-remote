package com.wangbin.ai.agent.contract.command;

import com.wangbin.ai.agent.contract.enums.ArtifactSourceType;

import java.util.Map;

/**
 * Requests the daemon to upload a workspace file snapshot for an existing platform artifact.
 * The daemon treats the path as untrusted cloud input and revalidates it against local policy.
 */
public record ArtifactFetchCommandPayload(
        String artifactId,
        String fileChangeId,
        String changeSetId,
        String relativePath,
        ArtifactSourceType sourceType,
        Map<String, Object> extensions
) implements AgentCommandPayload {

    public ArtifactFetchCommandPayload {
        sourceType = sourceType == null ? ArtifactSourceType.CHANGE_SET_FILE : sourceType;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
