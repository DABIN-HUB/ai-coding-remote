package com.wangbin.ai.agent.daemon.event.change;

import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.ChangeSetStatus;
import com.wangbin.ai.agent.contract.enums.FileChangeType;
import com.wangbin.ai.agent.contract.event.*;
import com.wangbin.ai.agent.daemon.adapter.codex.CodexSessionContext;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains the daemon-local command-level change set until the native turn
 * reaches a terminal lifecycle event. The accumulator is in-memory by design;
 * durable persistence happens after CHANGE_SET_FINALIZED reaches the control plane.
 */
@Component
public class AgentChangeSetAccumulator {

    private static final String CHANGE_SET_ID_EXTENSION = "changeSetId";
    private static final String WARNING_MESSAGE = "Codex file change path rejected by local workspace policy";
    private static final String CODEX_TURN_INTERRUPTED = "CODEX_TURN_INTERRUPTED";

    private final WorkspaceRelativePathNormalizer pathNormalizer;
    private final UnifiedDiffParser unifiedDiffParser;
    private final SensitivePathPolicy sensitivePathPolicy;
    private final DaemonChangeSetIdFactory idFactory;
    private final AgentCodexProperties properties;
    private final FileChangeMergePolicy mergePolicy = new FileChangeMergePolicy();
    private final Map<Key, ActiveChangeSet> activeChangeSets = new LinkedHashMap<>();

    public AgentChangeSetAccumulator(WorkspaceRelativePathNormalizer pathNormalizer,
                                     UnifiedDiffParser unifiedDiffParser,
                                     SensitivePathPolicy sensitivePathPolicy,
                                     DaemonChangeSetIdFactory idFactory,
                                     AgentCodexProperties properties) {
        this.pathNormalizer = pathNormalizer;
        this.unifiedDiffParser = unifiedDiffParser;
        this.sensitivePathPolicy = sensitivePathPolicy;
        this.idFactory = idFactory;
        this.properties = properties;
    }

    public synchronized List<AgentEvent> accept(AgentEvent event, CodexSessionContext context) {
        if (event == null || context == null) {
            return List.of();
        }
        String commandId = platformCommandId(event);
        if (commandId == null) {
            return List.of(event);
        }
        if (event.type() == AgentEventType.FILE_CHANGED && event.payload() instanceof FileChangedPayload payload) {
            return handleFileChanged(event, context, commandId, payload);
        }
        if (event.type() == AgentEventType.DIFF_UPDATED && event.payload() instanceof DiffUpdatedPayload payload) {
            return handleDiffUpdated(event, context, commandId, payload);
        }
        if (event.type() == AgentEventType.SESSION_IDLE || event.type() == AgentEventType.SESSION_COMPLETED) {
            return finalizeBefore(event, commandId, ChangeSetStatus.COMPLETED);
        }
        if (event.type() == AgentEventType.ERROR && event.payload() instanceof AgentErrorPayload payload) {
            if (payload.retryable()) {
                return List.of(event);
            }
            ChangeSetStatus status = CODEX_TURN_INTERRUPTED.equals(payload.code())
                    ? ChangeSetStatus.INTERRUPTED : ChangeSetStatus.FAILED;
            return finalizeBefore(event, commandId, status);
        }
        return List.of(event);
    }

    public synchronized void clearSession(String sessionId) {
        activeChangeSets.keySet().removeIf(key -> key.sessionId().equals(sessionId));
    }

    public synchronized void clear() {
        activeChangeSets.clear();
    }

    private List<AgentEvent> handleFileChanged(AgentEvent event, CodexSessionContext context, String commandId,
                                               FileChangedPayload payload) {
        String path = normalizePath(context, payload.path());
        if (path == null) {
            return List.of(warning(event));
        }
        String oldPath = normalizeOptionalPath(context, payload.oldPath());
        ActiveChangeSet changeSet = active(context, commandId);
        boolean redacted = payload.redacted() || sensitivePathPolicy.isSensitive(path)
                || sensitivePathPolicy.isSensitive(oldPath);
        changeSet.merge(new MutableFileChange(path, oldPath, payload.changeType(), payload.additions(),
                payload.deletions(), payload.binary(), payload.truncated(), redacted, null, null));
        FileChangedPayload normalized = new FileChangedPayload(path, oldPath, payload.changeType(),
                truncate(payload.summary(), properties.getFileSummaryMaxChars()), payload.additions(),
                payload.deletions(), payload.binary(), payload.truncated(), redacted,
                withChangeSetId(payload.extensions(), changeSet.changeSetId()));
        return List.of(copy(event, AgentEventType.FILE_CHANGED, normalized));
    }

    private List<AgentEvent> handleDiffUpdated(AgentEvent event, CodexSessionContext context, String commandId,
                                               DiffUpdatedPayload payload) {
        ActiveChangeSet changeSet = active(context, commandId);
        SanitizedDiff diff = unifiedDiffParser.parse(Path.of(context.workspacePath()), payload.diff());
        changeSet.applyDiff(diff);
        DiffUpdatedPayload normalized = new DiffUpdatedPayload(changeSet.changeSetId(), diff.diff(),
                diff.diffSha256(), diff.truncated(), diff.fileCount(), diff.additions(), diff.deletions(),
                withChangeSetId(payload.extensions(), changeSet.changeSetId()));
        return List.of(copy(event, AgentEventType.DIFF_UPDATED, normalized));
    }

    private List<AgentEvent> finalizeBefore(AgentEvent terminalEvent, String commandId, ChangeSetStatus status) {
        ActiveChangeSet changeSet = activeChangeSets.remove(new Key(terminalEvent.sessionId(), commandId));
        if (changeSet == null || changeSet.isEmpty()) {
            return List.of(terminalEvent);
        }
        return List.of(newEvent(terminalEvent, AgentEventType.CHANGE_SET_FINALIZED, changeSet.toPayload(status)),
                terminalEvent);
    }

    private ActiveChangeSet active(CodexSessionContext context, String commandId) {
        return activeChangeSets.computeIfAbsent(new Key(context.platformSessionId(), commandId),
                ignored -> new ActiveChangeSet(idFactory.changeSetId(), context.platformSessionId(), commandId));
    }

    private String normalizePath(CodexSessionContext context, String path) {
        try {
            return pathNormalizer.normalize(Path.of(context.workspacePath()), path);
        } catch (AgentCapabilityException ex) {
            return null;
        }
    }

    private String normalizeOptionalPath(CodexSessionContext context, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return normalizePath(context, path);
    }

    private AgentEvent warning(AgentEvent source) {
        return copy(source, AgentEventType.WARNING, new WarningPayload(WARNING_MESSAGE, Map.of()));
    }

    private AgentEvent copy(AgentEvent source, AgentEventType type, AgentEventPayload payload) {
        return new AgentEvent(source.eventId(), source.traceId(), source.tenantId(), source.userId(),
                source.deviceId(), source.projectId(), source.sessionId(), 0, source.agentType(), type,
                null, source.timestamp(), payload, source.extensions());
    }

    private AgentEvent newEvent(AgentEvent source, AgentEventType type, AgentEventPayload payload) {
        return new AgentEvent(null, source.traceId(), source.tenantId(), source.userId(),
                source.deviceId(), source.projectId(), source.sessionId(), 0, source.agentType(), type,
                null, null, payload, source.extensions());
    }

    private Map<String, Object> withChangeSetId(Map<String, Object> extensions, String changeSetId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (extensions != null) {
            result.putAll(extensions);
        }
        result.put(CHANGE_SET_ID_EXTENSION, changeSetId);
        return Map.copyOf(result);
    }

    private String platformCommandId(AgentEvent event) {
        Object value = event.extensions().get(AgentEventExtensionKeys.PLATFORM_COMMAND_ID);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private record Key(String sessionId, String commandId) {
    }

    private final class ActiveChangeSet {

        private final String changeSetId;
        private final String sessionId;
        private final String commandId;
        private final Map<String, MutableFileChange> files = new LinkedHashMap<>();
        private String diff = "";
        private String diffSha256;
        private boolean diffTruncated;
        private boolean filesTruncated;
        private Instant firstChangedAt = Instant.now();

        private ActiveChangeSet(String changeSetId, String sessionId, String commandId) {
            this.changeSetId = changeSetId;
            this.sessionId = sessionId;
            this.commandId = commandId;
        }

        private String changeSetId() {
            return changeSetId;
        }

        private boolean isEmpty() {
            return files.isEmpty() && (diff == null || diff.isBlank());
        }

        private void merge(MutableFileChange next) {
            if (!files.containsKey(next.path()) && files.size() >= properties.getChangeSetMaxFiles()) {
                filesTruncated = true;
                return;
            }
            firstChangedAt = firstChangedAt == null ? Instant.now() : firstChangedAt;
            files.merge(next.path(), next, (current, incoming) -> current.merge(incoming));
        }

        private void applyDiff(SanitizedDiff sanitizedDiff) {
            diff = sanitizedDiff.diff();
            diffSha256 = sanitizedDiff.diffSha256();
            diffTruncated = sanitizedDiff.truncated();
            filesTruncated = filesTruncated || sanitizedDiff.filesTruncated();
            for (UnifiedDiffFile file : sanitizedDiff.files()) {
                merge(new MutableFileChange(file));
            }
        }

        private ChangeSetFinalizedPayload toPayload(ChangeSetStatus status) {
            List<ChangedFileSummary> summaries = new ArrayList<>();
            int additions = 0;
            int deletions = 0;
            for (MutableFileChange file : files.values()) {
                additions += file.additions() == null ? 0 : file.additions();
                deletions += file.deletions() == null ? 0 : file.deletions();
                summaries.add(file.summary());
            }
            return new ChangeSetFinalizedPayload(changeSetId, status, summaries.size(), additions, deletions,
                    diff, diffSha256, diffTruncated, filesTruncated, summaries, Instant.now(),
                    Map.of("firstChangedAt", firstChangedAt.toString()));
        }
    }

    private final class MutableFileChange {

        private final String path;
        private String oldPath;
        private FileChangeType changeType;
        private Integer additions;
        private Integer deletions;
        private boolean binary;
        private boolean truncated;
        private boolean redacted;
        private String patchText;
        private String patchSha256;

        private MutableFileChange(String path, String oldPath, FileChangeType changeType, Integer additions,
                                  Integer deletions, boolean binary, boolean truncated, boolean redacted,
                                  String patchText, String patchSha256) {
            this.path = path;
            this.oldPath = oldPath;
            this.changeType = changeType == null ? FileChangeType.UNKNOWN : changeType;
            this.additions = additions;
            this.deletions = deletions;
            this.binary = binary;
            this.truncated = truncated;
            this.redacted = redacted;
            this.patchText = patchText;
            this.patchSha256 = patchSha256;
        }

        private MutableFileChange(UnifiedDiffFile file) {
            this(file.path(), file.oldPath(), file.changeType(), file.additions(), file.deletions(), file.binary(),
                    file.patchTruncated(), file.redacted(), file.patchText(), file.patchSha256());
        }

        private String path() {
            return path;
        }

        private Integer additions() {
            return additions;
        }

        private Integer deletions() {
            return deletions;
        }

        private MutableFileChange merge(MutableFileChange incoming) {
            oldPath = incoming.oldPath == null ? oldPath : incoming.oldPath;
            changeType = mergePolicy.merge(changeType, incoming.changeType);
            additions = incoming.additions == null ? additions : incoming.additions;
            deletions = incoming.deletions == null ? deletions : incoming.deletions;
            binary = binary || incoming.binary;
            truncated = truncated || incoming.truncated;
            redacted = redacted || incoming.redacted;
            patchText = incoming.patchText == null ? patchText : incoming.patchText;
            patchSha256 = incoming.patchSha256 == null ? patchSha256 : incoming.patchSha256;
            return this;
        }

        private ChangedFileSummary summary() {
            return new ChangedFileSummary(path, oldPath, changeType, additions, deletions, binary, truncated,
                    redacted, patchText, patchSha256);
        }
    }
}
