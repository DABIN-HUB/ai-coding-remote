package com.wangbin.ai.agent.daemon.event.change;

import java.util.List;

public record SanitizedDiff(
        String diff,
        String diffSha256,
        boolean truncated,
        boolean filesTruncated,
        int fileCount,
        int additions,
        int deletions,
        List<UnifiedDiffFile> files
) {

    public SanitizedDiff {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
