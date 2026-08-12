package com.wangbin.ai.agent.daemon.event.change;

import com.wangbin.ai.agent.contract.enums.FileChangeType;

public record UnifiedDiffFile(
        String path,
        String oldPath,
        FileChangeType changeType,
        int additions,
        int deletions,
        boolean binary,
        String patchText,
        String patchSha256,
        boolean patchTruncated,
        boolean redacted
) {
}
