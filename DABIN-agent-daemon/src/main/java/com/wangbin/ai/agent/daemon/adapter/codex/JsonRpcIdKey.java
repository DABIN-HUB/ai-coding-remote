package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Preserves the exact JSON-RPC id shape. Numeric {@code 1} and string
 * {@code "1"} are different native requests and must not collide.
 */
public record JsonRpcIdKey(String canonicalJson) {

    public static JsonRpcIdKey from(JsonNode id) {
        return new JsonRpcIdKey(id == null || id.isNull() ? "null" : id.toString());
    }
}
