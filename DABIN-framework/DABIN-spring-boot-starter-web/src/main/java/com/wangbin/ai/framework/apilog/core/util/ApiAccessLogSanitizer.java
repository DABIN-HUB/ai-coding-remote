package com.wangbin.ai.framework.apilog.core.util;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.ai.framework.common.pojo.CommonResult;
import com.wangbin.ai.framework.common.util.json.JsonUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.wangbin.ai.framework.common.util.json.JsonUtils.toJsonString;

/**
 * Sanitizes request/response payloads before they enter console or durable API
 * access logs. Agent pairing and credential fields are authentication material
 * and must not be persisted by development-profile request logging.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ApiAccessLogSanitizer {

    private static final String[] DEFAULT_SANITIZE_KEYS = new String[]{
            "password", "token", "accessToken", "refreshToken",
            "pairingCode", "credentialSecret", "deviceSecret", "secret",
            "relayTicket", "ticket", "apiKey"
    };

    public static String sanitizeMap(Map<String, ?> map, String[] sanitizeKeys) {
        if (CollUtil.isEmpty(map)) {
            return null;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(map);
        if (sanitizeKeys != null) {
            MapUtil.removeAny(sanitized, sanitizeKeys);
        }
        MapUtil.removeAny(sanitized, DEFAULT_SANITIZE_KEYS);
        return JsonUtils.toJsonString(sanitized);
    }

    public static String sanitizeJson(String jsonString, String[] sanitizeKeys) {
        if (StrUtil.isEmpty(jsonString)) {
            return null;
        }
        try {
            JsonNode rootNode = JsonUtils.parseTree(jsonString);
            sanitizeJson(rootNode, sanitizeKeys);
            return JsonUtils.toJsonString(rootNode);
        } catch (Exception e) {
            log.error("[sanitizeJson][request log sanitization failed]", e);
            return null;
        }
    }

    public static String sanitizeJson(CommonResult<?> commonResult, String[] sanitizeKeys) {
        if (commonResult == null) {
            return null;
        }
        String jsonString = toJsonString(commonResult);
        try {
            JsonNode rootNode = JsonUtils.parseTree(jsonString);
            sanitizeJson(rootNode.get("data"), sanitizeKeys);
            return JsonUtils.toJsonString(rootNode);
        } catch (Exception e) {
            log.error("[sanitizeJson][response log sanitization failed]", e);
            return null;
        }
    }

    public static String sanitizePayloadForConsole(String requestBody, Map<String, String> queryString,
                                                   String[] sanitizeKeys) {
        String sanitizedBody = sanitizeJson(requestBody, sanitizeKeys);
        if (StrUtil.isNotEmpty(sanitizedBody)) {
            return sanitizedBody;
        }
        return sanitizeMap(queryString, sanitizeKeys);
    }

    private static void sanitizeJson(JsonNode node, String[] sanitizeKeys) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode childNode : node) {
                sanitizeJson(childNode, sanitizeKeys);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        var iterator = node.properties().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (ArrayUtil.contains(sanitizeKeys, entry.getKey())
                    || ArrayUtil.contains(DEFAULT_SANITIZE_KEYS, entry.getKey())) {
                iterator.remove();
                continue;
            }
            sanitizeJson(entry.getValue(), sanitizeKeys);
        }
    }
}
