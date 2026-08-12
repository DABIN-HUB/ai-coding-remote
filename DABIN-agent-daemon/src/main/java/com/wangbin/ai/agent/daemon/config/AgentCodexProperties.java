package com.wangbin.ai.agent.daemon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties(prefix = "agent.codex")
public class AgentCodexProperties {

    private String executable = "codex";

    private Duration startupTimeout = Duration.ofSeconds(30);

    private Duration requestTimeout = Duration.ofSeconds(60);

    private boolean experimentalApi = true;

    @Min(1)
    private int pendingPermissionCapacity = 256;

    @Min(64)
    private int permissionSnapshotMaxChars = 4096;

    @Min(1)
    private int changeSetMaxFiles = 200;

    @Min(1024)
    private int diffSnapshotMaxChars = 512 * 1024;

    @Min(128)
    private int fileSummaryMaxChars = 2048;

    @Min(1024)
    private int filePatchMaxChars = 128 * 1024;

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }

    public Duration getStartupTimeout() {
        return startupTimeout;
    }

    public void setStartupTimeout(Duration startupTimeout) {
        this.startupTimeout = startupTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public boolean isExperimentalApi() {
        return experimentalApi;
    }

    public void setExperimentalApi(boolean experimentalApi) {
        this.experimentalApi = experimentalApi;
    }

    public int getPendingPermissionCapacity() {
        return pendingPermissionCapacity;
    }

    public void setPendingPermissionCapacity(int pendingPermissionCapacity) {
        this.pendingPermissionCapacity = pendingPermissionCapacity;
    }

    public int getPermissionSnapshotMaxChars() {
        return permissionSnapshotMaxChars;
    }

    public void setPermissionSnapshotMaxChars(int permissionSnapshotMaxChars) {
        this.permissionSnapshotMaxChars = permissionSnapshotMaxChars;
    }

    public int getChangeSetMaxFiles() {
        return changeSetMaxFiles;
    }

    public void setChangeSetMaxFiles(int changeSetMaxFiles) {
        this.changeSetMaxFiles = changeSetMaxFiles;
    }

    public int getDiffSnapshotMaxChars() {
        return diffSnapshotMaxChars;
    }

    public void setDiffSnapshotMaxChars(int diffSnapshotMaxChars) {
        this.diffSnapshotMaxChars = diffSnapshotMaxChars;
    }

    public int getFileSummaryMaxChars() {
        return fileSummaryMaxChars;
    }

    public void setFileSummaryMaxChars(int fileSummaryMaxChars) {
        this.fileSummaryMaxChars = fileSummaryMaxChars;
    }

    public int getFilePatchMaxChars() {
        return filePatchMaxChars;
    }

    public void setFilePatchMaxChars(int filePatchMaxChars) {
        this.filePatchMaxChars = filePatchMaxChars;
    }

}
