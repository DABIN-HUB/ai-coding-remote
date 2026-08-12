package com.wangbin.ai.agent.contract.event;

import com.wangbin.ai.agent.contract.enums.ChangeSetStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ChangeSetFinalizedPayload(
        String changeSetId,
        ChangeSetStatus status,
        Integer fileCount,
        Integer additions,
        Integer deletions,
        String diff,
        String diffSha256,
        boolean diffTruncated,
        boolean filesTruncated,
        List<ChangedFileSummary> files,
        Instant completedAt,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public ChangeSetFinalizedPayload {
        status = status == null ? ChangeSetStatus.FAILED : status;
        fileCount = fileCount == null ? 0 : fileCount;
        additions = additions == null ? 0 : additions;
        deletions = deletions == null ? 0 : deletions;
        files = files == null ? List.of() : List.copyOf(files);
        completedAt = completedAt == null ? Instant.now() : completedAt;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
