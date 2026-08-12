package com.wangbin.ai.module.agent.framework.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "agent.artifact")
public class AgentArtifactProperties {

    /**
     * Optional infra_file_config id. Null means use the current master file config.
     */
    private Long fileConfigId;

    @Positive
    private long maxFileSize = 100L * 1024L * 1024L;

    /**
     * Max artifact size for providers whose FileClient falls back to byte[] upload/download.
     */
    @Positive
    private long nonStreamingMaxFileSize = 5L * 1024L * 1024L;

    @NotNull
    private Duration uploadTicketTtl = Duration.ofMinutes(5);

    @NotNull
    private Duration retention = Duration.ofDays(7);

    @Positive
    private int cleanupBatchSize = 50;

    @NotNull
    private Duration cleanupInterval = Duration.ofMinutes(1);

    public Long getFileConfigId() {
        return fileConfigId;
    }

    public void setFileConfigId(Long fileConfigId) {
        this.fileConfigId = fileConfigId;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public long getNonStreamingMaxFileSize() {
        return nonStreamingMaxFileSize;
    }

    public void setNonStreamingMaxFileSize(long nonStreamingMaxFileSize) {
        this.nonStreamingMaxFileSize = nonStreamingMaxFileSize;
    }

    public Duration getUploadTicketTtl() {
        return uploadTicketTtl;
    }

    public void setUploadTicketTtl(Duration uploadTicketTtl) {
        this.uploadTicketTtl = uploadTicketTtl;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }
}
