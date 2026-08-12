package com.wangbin.ai.agent.contract.event;

import java.util.Map;

public record DiffUpdatedPayload(
        String changeSetId,
        String diff,
        String diffSha256,
        boolean truncated,
        Integer fileCount,
        Integer additions,
        Integer deletions,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public DiffUpdatedPayload {
        fileCount = fileCount == null ? 0 : fileCount;
        additions = additions == null ? 0 : additions;
        deletions = deletions == null ? 0 : deletions;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
