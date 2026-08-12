package com.wangbin.ai.agent.contract.event;

import com.wangbin.ai.agent.contract.enums.FileChangeType;

import java.util.Map;

public record FileChangedPayload(
        String path,
        String oldPath,
        FileChangeType changeType,
        String summary,
        Integer additions,
        Integer deletions,
        boolean binary,
        boolean truncated,
        boolean redacted,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public FileChangedPayload {
        changeType = changeType == null ? FileChangeType.UNKNOWN : changeType;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
