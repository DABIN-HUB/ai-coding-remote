package com.wangbin.ai.module.agent.service.artifact;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentArtifactCleanupJob {

    private final AgentArtifactService artifactService;

    @Scheduled(fixedDelayString = "${agent.artifact.cleanup-interval-millis:60000}")
    public void cleanupExpired() {
        int count = artifactService.cleanupExpired();
        if (count > 0) {
            log.info("expired agent artifacts cleaned: count={}", count);
        }
    }
}
