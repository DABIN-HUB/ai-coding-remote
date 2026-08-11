package com.wangbin.ai.agent.daemon.adapter.codex.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

public record CodexRpcMessage(
        CodexRpcMessageKind kind,
        JsonNode id,
        String method,
        JsonNode params,
        JsonNode result,
        JsonNode error
) {

    public static CodexRpcMessage notification(String method, JsonNode params) {
        return new CodexRpcMessage(CodexRpcMessageKind.NOTIFICATION, null, method, params, null, null);
    }

    public static CodexRpcMessage serverRequest(String id, String method, JsonNode params) {
        return serverRequest(id == null ? null : TextNode.valueOf(id), method, params);
    }

    public static CodexRpcMessage serverRequest(JsonNode id, String method, JsonNode params) {
        return new CodexRpcMessage(CodexRpcMessageKind.SERVER_REQUEST, id, method, params, null, null);
    }

    public String idText() {
        return id == null || id.isNull() ? null : id.asText();
    }

}
