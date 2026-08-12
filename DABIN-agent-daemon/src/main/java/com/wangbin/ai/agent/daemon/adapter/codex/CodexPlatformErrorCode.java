package com.wangbin.ai.agent.daemon.adapter.codex;

/**
 * Daemon-local normalized Codex error codes. These values intentionally stay
 * outside the shared contract because they are anti-corruption mapping output,
 * not platform protocol enum variants.
 */
enum CodexPlatformErrorCode {

    CODEX_CONTEXT_WINDOW_EXCEEDED,
    CODEX_SESSION_BUDGET_EXCEEDED,
    CODEX_USAGE_LIMIT_EXCEEDED,
    CODEX_SERVER_OVERLOADED,
    CODEX_CYBER_POLICY,
    CODEX_INTERNAL_SERVER_ERROR,
    CODEX_UNAUTHORIZED,
    CODEX_BAD_REQUEST,
    CODEX_THREAD_ROLLBACK_FAILED,
    CODEX_SANDBOX_ERROR,
    CODEX_HTTP_CONNECTION_FAILED,
    CODEX_RESPONSE_STREAM_CONNECTION_FAILED,
    CODEX_RESPONSE_STREAM_DISCONNECTED,
    CODEX_TOO_MANY_FAILED_ATTEMPTS,
    CODEX_ACTIVE_TURN_NOT_STEERABLE,
    CODEX_TURN_INTERRUPTED,
    CODEX_TURN_FAILED,
    CODEX_OTHER
}
