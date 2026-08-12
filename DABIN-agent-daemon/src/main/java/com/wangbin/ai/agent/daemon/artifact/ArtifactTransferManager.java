package com.wangbin.ai.agent.daemon.artifact;

import com.wangbin.ai.agent.contract.command.AgentCommand;
import com.wangbin.ai.agent.contract.command.ArtifactFetchCommandPayload;
import com.wangbin.ai.agent.daemon.cloud.controlplane.ControlPlaneClient;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.event.change.SensitivePathPolicy;
import com.wangbin.ai.agent.daemon.event.change.WorkspaceRelativePathNormalizer;
import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.project.LocalProject;
import com.wangbin.ai.agent.daemon.project.LocalProjectRegistry;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ArtifactTransferManager {

    private static final Logger log = LoggerFactory.getLogger(ArtifactTransferManager.class);

    private static final String ERROR_SOURCE_INVALID = "ARTIFACT_SOURCE_INVALID";
    private static final String ERROR_SOURCE_CHANGED = "ARTIFACT_SOURCE_CHANGED";
    private static final String ERROR_SIZE_EXCEEDED = "ARTIFACT_SIZE_EXCEEDED";
    private static final String ERROR_UPLOAD_FAILED = "ARTIFACT_UPLOAD_FAILED";
    private static final int ERROR_MESSAGE_MAX = 1024;
    private final LocalProjectRegistry localProjectRegistry;
    private final WorkspaceManager workspaceManager;
    private final WorkspaceRelativePathNormalizer pathNormalizer;
    private final SensitivePathPolicy sensitivePathPolicy;
    private final ControlPlaneClient controlPlaneClient;
    private final AgentDaemonProperties properties;
    private final ExecutorService executorService;

    public ArtifactTransferManager(LocalProjectRegistry localProjectRegistry, WorkspaceManager workspaceManager,
                                   WorkspaceRelativePathNormalizer pathNormalizer,
                                   SensitivePathPolicy sensitivePathPolicy, ControlPlaneClient controlPlaneClient,
                                   AgentDaemonProperties properties,
                                   @Qualifier("agentArtifactTransferExecutor") ExecutorService executorService) {
        this.localProjectRegistry = localProjectRegistry;
        this.workspaceManager = workspaceManager;
        this.pathNormalizer = pathNormalizer;
        this.sensitivePathPolicy = sensitivePathPolicy;
        this.controlPlaneClient = controlPlaneClient;
        this.properties = properties;
        this.executorService = executorService;
    }

    public PendingArtifactTransfer submit(AgentCommand command, ArtifactFetchCommandPayload payload,
                                          DeviceCredentialState credential) {
        QueuedArtifactTransfer transfer = new QueuedArtifactTransfer(command, payload, credential);
        try {
            Future<?> future = executorService.submit(transfer);
            transfer.setFuture(future);
            return transfer;
        } catch (RejectedExecutionException ex) {
            return null;
        }
    }

    public interface PendingArtifactTransfer {

        void start();

        void cancel();
    }

    private void transfer(AgentCommand command, ArtifactFetchCommandPayload payload, DeviceCredentialState credential) {
        try {
            LocalArtifactFile source = resolveSource(command, payload);
            HashResult hash = hashStable(source.path(), payload.relativePath());
            if (hash.size() > properties.getArtifact().getMaxFileSize()) {
                reportFailure(credential, payload.artifactId(), ERROR_SIZE_EXCEEDED, "artifact file size exceeds limit");
                return;
            }
            ArtifactPrepareUploadResponse prepare = controlPlaneClient.prepareArtifactUpload(credential,
                    new ArtifactPrepareUploadRequest(payload.artifactId(), hash.size(), hash.sha256(),
                            source.contentType(), hash.lastModifiedTime()));
            if (Boolean.TRUE.equals(prepare.alreadyReady())) {
                return;
            }
            controlPlaneClient.uploadArtifact(credential, payload.artifactId(), prepare.uploadTicket(),
                    source.path(), source.contentType(), hash.size());
        } catch (AgentCapabilityException ex) {
            reportFailure(credential, payload.artifactId(), ERROR_SOURCE_INVALID, sanitize(ex.getMessage()));
        } catch (SourceChangedException ex) {
            reportFailure(credential, payload.artifactId(), ERROR_SOURCE_CHANGED, "artifact source changed during hash");
        } catch (RuntimeException ex) {
            log.warn("artifact transfer failed: artifactId={}, errorType={}, error={}",
                    payload.artifactId(), ex.getClass().getSimpleName(), sanitize(ex.getMessage()));
            reportFailure(credential, payload.artifactId(), ERROR_UPLOAD_FAILED, sanitize(ex.getMessage()));
        }
    }

    public void reportFailure(DeviceCredentialState credential, String artifactId, String code, String message) {
        try {
            controlPlaneClient.reportArtifactFailure(credential,
                    new ArtifactReportFailureRequest(artifactId, code, sanitize(message)));
        } catch (RuntimeException ex) {
            log.warn("artifact failure report failed: artifactId={}, error={}", artifactId, sanitize(ex.getMessage()));
        }
    }

    public boolean canResolve(AgentCommand command, ArtifactFetchCommandPayload payload) {
        resolveSource(command, payload);
        return true;
    }

    private LocalArtifactFile resolveSource(AgentCommand command, ArtifactFetchCommandPayload payload) {
        if (payload == null || payload.artifactId() == null || payload.relativePath() == null) {
            throw new AgentCapabilityException("artifact payload is invalid");
        }
        Optional<LocalProject> localProject = localProjectRegistry.findByPlatformProjectId(command.projectId());
        LocalProject project = localProject.orElseThrow(() -> new AgentCapabilityException("project is not registered locally"));
        Path realWorkspace = workspaceManager.validateWorkspace(project.realWorkspace().toString());
        if (!realWorkspace.equals(project.realWorkspace())) {
            throw new AgentCapabilityException("workspace real path changed");
        }
        String relativePath = pathNormalizer.normalize(realWorkspace, payload.relativePath());
        if (sensitivePathPolicy.isSensitive(relativePath)) {
            throw new AgentCapabilityException("artifact source path is sensitive");
        }
        Path source = workspaceManager.resolveWithinWorkspace(realWorkspace, relativePath);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new AgentCapabilityException("artifact source is not a regular file");
        }
        try {
            return new LocalArtifactFile(source, Files.probeContentType(source));
        } catch (IOException ex) {
            return new LocalArtifactFile(source, "application/octet-stream");
        }
    }

    private HashResult hashStable(Path source, String relativePath) {
        try {
            long beforeSize = Files.size(source);
            FileTime beforeModified = Files.getLastModifiedTime(source);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(source);
                 DigestInputStream digestInputStream = new DigestInputStream(in, digest)) {
                digestInputStream.transferTo(OutputStreamDiscard.INSTANCE);
            }
            long afterSize = Files.size(source);
            FileTime afterModified = Files.getLastModifiedTime(source);
            if (beforeSize != afterSize || !beforeModified.equals(afterModified)) {
                throw new SourceChangedException();
            }
            return new HashResult(afterSize, HexFormat.of().formatHex(digest.digest()),
                    LocalDateTime.ofInstant(afterModified.toInstant(), ZoneId.systemDefault()));
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new AgentCapabilityException("artifact source cannot be read: " + relativePath, ex);
        }
    }

    private String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String sanitized = message.replace('\r', ' ').replace('\n', ' ');
        if (sanitized.length() <= ERROR_MESSAGE_MAX) {
            return sanitized;
        }
        return sanitized.substring(0, ERROR_MESSAGE_MAX);
    }

    private record LocalArtifactFile(Path path, String contentType) {
        private LocalArtifactFile {
            contentType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        }
    }

    private record HashResult(long size, String sha256, LocalDateTime lastModifiedTime) {
    }

    private static final class SourceChangedException extends RuntimeException {
    }

    private final class QueuedArtifactTransfer implements Runnable, PendingArtifactTransfer {

        private final AgentCommand command;
        private final ArtifactFetchCommandPayload payload;
        private final DeviceCredentialState credential;
        private final CountDownLatch startGate = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Future<?> future;

        private QueuedArtifactTransfer(AgentCommand command, ArtifactFetchCommandPayload payload,
                                       DeviceCredentialState credential) {
            this.command = command;
            this.payload = payload;
            this.credential = credential;
        }

        private void setFuture(Future<?> future) {
            this.future = future;
        }

        @Override
        public void run() {
            try {
                startGate.await();
                if (!cancelled.get()) {
                    transfer(command, payload, credential);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void start() {
            startGate.countDown();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            Future<?> currentFuture = future;
            if (currentFuture != null) {
                currentFuture.cancel(true);
            }
            startGate.countDown();
        }
    }

    private static final class OutputStreamDiscard extends java.io.OutputStream {

        private static final OutputStreamDiscard INSTANCE = new OutputStreamDiscard();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
