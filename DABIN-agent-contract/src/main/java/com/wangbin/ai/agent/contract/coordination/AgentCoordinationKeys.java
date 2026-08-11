package com.wangbin.ai.agent.contract.coordination;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Redis coordination keys shared by Control Plane, Relay and Daemon-facing code.
 * This class is intentionally pure Java and contains no Redis client dependency.
 */
public final class AgentCoordinationKeys {

    private static final String PAIRING_PREFIX = "agent:pairing:";
    private static final String RELAY_TICKET_PREFIX = "agent:relay:ticket:";
    private static final String DEVICE_PRESENCE_PREFIX = "agent:presence:device:";
    private static final String DEVICE_ROUTE_PREFIX = "agent:route:device:";
    private static final String USER_ROUTE_PREFIX = "agent:route:user:";
    private static final String RELAY_NODE_PREFIX = "agent:relay:node:";
    private static final String PAIRING_LOCK_PREFIX = "agent:lock:pair:";
    private static final String SHA_256_ALGORITHM = "SHA-256";

    private AgentCoordinationKeys() {
    }

    public static String pairing(String code) {
        return PAIRING_PREFIX + code;
    }

    public static String relayTicket(String ticket) {
        return RELAY_TICKET_PREFIX + ticket;
    }

    public static String devicePresence(String deviceId) {
        return DEVICE_PRESENCE_PREFIX + deviceId;
    }

    public static String deviceRoute(String deviceId) {
        return DEVICE_ROUTE_PREFIX + deviceId;
    }

    public static String userRoute(Long userId, String connectionId) {
        return USER_ROUTE_PREFIX + userId + ":" + connectionId;
    }

    public static String relayNode(String relayNodeId) {
        return RELAY_NODE_PREFIX + relayNodeId;
    }

    public static String pairingLock(Long tenantId, Long userId, String installationId) {
        return PAIRING_LOCK_PREFIX + tenantId + ":" + userId + ":" + sha256UrlSafe(installationId);
    }

    private static String sha256UrlSafe(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256_ALGORITHM);
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }
}
