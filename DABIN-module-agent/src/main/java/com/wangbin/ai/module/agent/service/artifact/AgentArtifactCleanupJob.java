package com.wangbin.ai.module.agent.service.artifact;

import com.wangbin.ai.framework.tenant.core.job.TenantJob;
import com.wangbin.ai.module.agent.framework.config.AgentArtifactProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentArtifactCleanupJob {

    private final AgentArtifactService artifactService;
    private final AgentArtifactProperties artifactProperties;

    @TenantJob
    @Scheduled(fixedDelayString = "#{@agentArtifactCleanupJob.cleanupIntervalMillis()}")
    public void cleanupExpired() {
        int count = artifactService.cleanupExpired();
        if (count > 0) {
            log.info("expired agent artifacts cleaned: count={}", count);
        }
    }

    public long cleanupIntervalMillis() {
        return artifactProperties.getCleanupInterval().toMillis();
    }
}
