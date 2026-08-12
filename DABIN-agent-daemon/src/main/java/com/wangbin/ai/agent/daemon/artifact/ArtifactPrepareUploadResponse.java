package com.wangbin.ai.agent.daemon.artifact;

public record ArtifactPrepareUploadResponse(
        Boolean alreadyReady,
        String uploadTicket,
        String uploadPath
) {
}
