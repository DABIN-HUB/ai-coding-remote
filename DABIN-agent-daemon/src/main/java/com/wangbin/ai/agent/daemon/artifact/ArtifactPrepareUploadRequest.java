package com.wangbin.ai.agent.daemon.artifact;

import java.time.LocalDateTime;

public record ArtifactPrepareUploadRequest(
        String artifactId,
        long fileSize,
        String sha256,
        String contentType,
        LocalDateTime sourceLastModifiedTime
) {
}
