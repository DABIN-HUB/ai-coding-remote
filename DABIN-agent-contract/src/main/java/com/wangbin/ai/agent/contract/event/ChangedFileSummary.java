package com.wangbin.ai.agent.contract.event;

import com.wangbin.ai.agent.contract.enums.FileChangeType;

public record ChangedFileSummary(
        String path,
        String oldPath,
        FileChangeType changeType,
        Integer additions,
        Integer deletions,
        boolean binary,
        boolean truncated,
        boolean redacted,
        String patchText,
        String patchSha256
) {
}
