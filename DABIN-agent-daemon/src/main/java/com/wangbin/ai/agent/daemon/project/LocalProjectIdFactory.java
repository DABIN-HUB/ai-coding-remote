package com.wangbin.ai.agent.daemon.project;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Generates a stable local project id from the real workspace path. The id is
 * local-authority metadata and is safe to persist in the daemon state file.
 */
public final class LocalProjectIdFactory {

    private static final String LOCAL_PROJECT_ID_PREFIX = "local_";
    private static final String SHA_256_ALGORITHM = "SHA-256";

    private LocalProjectIdFactory() {
    }

    public static String stableLocalProjectId(Path realWorkspace) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256_ALGORITHM);
            byte[] hash = digest.digest(realWorkspace.toString().getBytes(StandardCharsets.UTF_8));
            return LOCAL_PROJECT_ID_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

}
