package com.wangbin.ai.agent.daemon.artifact;

public record ArtifactReportFailureRequest(
        String artifactId,
        String errorCode,
        String errorMessage
) {
}
