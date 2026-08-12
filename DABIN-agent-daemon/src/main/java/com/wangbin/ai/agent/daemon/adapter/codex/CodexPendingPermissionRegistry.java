package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class CodexPendingPermissionRegistry {

    private final AgentCodexProperties properties;
    private final ConcurrentMap<String, PendingPermission> byPermissionId = new ConcurrentHashMap<>();
    private final ConcurrentMap<JsonRpcIdKey, PendingPermission> byNativeRequestId = new ConcurrentHashMap<>();
    private final Object monitor = new Object();

    public CodexPendingPermissionRegistry(AgentCodexProperties properties) {
        this.properties = properties;
    }

    public RegistrationResult register(PendingPermission permission) {
        synchronized (monitor) {
            PendingPermission existing = byNativeRequestId.get(permission.nativeRequestKey());
            if (existing != null) {
                return new RegistrationResult(existing, false);
            }
            if (byPermissionId.size() >= properties.getPendingPermissionCapacity()) {
                throw new CodexPendingPermissionCapacityException("pending Codex permission capacity exceeded");
            }
            byPermissionId.put(permission.permissionId(), permission);
            byNativeRequestId.put(permission.nativeRequestKey(), permission);
            return new RegistrationResult(permission, true);
        }
    }

    public Optional<PendingPermission> findByPermissionId(String permissionId) {
        return Optional.ofNullable(byPermissionId.get(permissionId));
    }

    public Optional<PendingPermission> resolveByNativeRequestId(JsonNode nativeRequestId) {
        synchronized (monitor) {
            PendingPermission permission = byNativeRequestId.get(JsonRpcIdKey.from(nativeRequestId));
            if (permission == null || !permission.markResolved()) {
                return Optional.empty();
            }
            removeLocked(permission);
            return Optional.of(permission);
        }
    }

    public List<PendingPermission> removeBySession(String platformSessionId) {
        synchronized (monitor) {
            List<PendingPermission> removed = new ArrayList<>();
            for (PendingPermission permission : byPermissionId.values()) {
                if (permission.platformSessionId().equals(platformSessionId)) {
                    permission.markResolved();
                    removeLocked(permission);
                    removed.add(permission);
                }
            }
            return removed;
        }
    }

    public void clear() {
        synchronized (monitor) {
            byPermissionId.clear();
            byNativeRequestId.clear();
        }
    }

    public int size() {
        return byPermissionId.size();
    }

    private void removeLocked(PendingPermission permission) {
        byPermissionId.remove(permission.permissionId());
        byNativeRequestId.remove(permission.nativeRequestKey());
    }

    public record RegistrationResult(PendingPermission permission, boolean created) {
    }
}
