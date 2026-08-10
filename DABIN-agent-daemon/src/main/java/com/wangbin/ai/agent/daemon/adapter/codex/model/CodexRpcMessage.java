package com.wangbin.ai.agent.daemon.adapter.codex.model;

import com.fasterxml.jackson.databind.JsonNode;

public record CodexRpcMessage(
        CodexRpcMessageKind kind,
        String id,
        String method,
        JsonNode params,
        JsonNode result,
        JsonNode error
) {

    public static CodexRpcMessage notification(String method, JsonNode params) {
        return new CodexRpcMessage(CodexRpcMessageKind.NOTIFICATION, null, method, params, null, null);
    }

    public static CodexRpcMessage serverRequest(String id, String method, JsonNode params) {
        return new CodexRpcMessage(CodexRpcMessageKind.SERVER_REQUEST, id, method, params, null, null);
    }

}
