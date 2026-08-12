package com.wangbin.ai.agent.daemon.adapter.codex;

import java.util.Map;

record CodexErrorClassification(
        CodexPlatformErrorCode code,
        String message,
        boolean retryable,
        Map<String, Object> extensions
) {

    CodexErrorClassification {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
