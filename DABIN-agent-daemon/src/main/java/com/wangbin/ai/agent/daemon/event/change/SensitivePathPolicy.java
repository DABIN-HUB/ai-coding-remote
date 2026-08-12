package com.wangbin.ai.agent.daemon.event.change;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Redacts patches for well-known secret-bearing files before they leave the daemon.
 */
@Component
public class SensitivePathPolicy {

    public boolean isSensitive(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        return ".env".equals(fileName)
                || fileName.startsWith(".env.")
                || fileName.endsWith(".pem")
                || fileName.endsWith(".key")
                || "id_rsa".equals(fileName)
                || ".id_rsa".equals(fileName)
                || "id_ed25519".equals(fileName)
                || ".id_ed25519".equals(fileName)
                || "auth.json".equals(fileName)
                || hasCredentialsSegment(normalized);
    }

    private boolean hasCredentialsSegment(String path) {
        if ("credentials".equals(path)) {
            return true;
        }
        return path.startsWith("credentials/")
                || path.endsWith("/credentials")
                || path.contains("/credentials/");
    }
}
