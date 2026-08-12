package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import com.wangbin.ai.agent.contract.enums.PermissionType;
import com.wangbin.ai.agent.contract.permission.CommandExecutionPermissionDetail;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodexPendingPermissionRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void numericAndStringJsonRpcIdsDoNotCollide() throws Exception {
        CodexPendingPermissionRegistry registry = registry(4);
        PendingPermission numeric = permission("perm_num", objectMapper.readTree("1"));
        PendingPermission string = permission("perm_str", objectMapper.readTree("\"1\""));

        registry.register(numeric);
        registry.register(string);

        assertThat(registry.resolveByNativeRequestId(objectMapper.readTree("1"))).contains(numeric);
        assertThat(registry.resolveByNativeRequestId(objectMapper.readTree("\"1\""))).contains(string);
    }

    @Test
    void duplicateNativeRequestReturnsExistingPermissionWithoutGrowingRegistry() throws Exception {
        CodexPendingPermissionRegistry registry = registry(4);
        PendingPermission first = permission("perm_1", objectMapper.readTree("123"));
        PendingPermission duplicate = permission("perm_2", objectMapper.readTree("123"));

        assertThat(registry.register(first).created()).isTrue();
        CodexPendingPermissionRegistry.RegistrationResult result = registry.register(duplicate);

        assertThat(result.created()).isFalse();
        assertThat(result.permission()).isEqualTo(first);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void capacityIsBoundedAndDecisionStateIsSingleWinner() throws Exception {
        CodexPendingPermissionRegistry registry = registry(1);
        PendingPermission permission = permission("perm_1", objectMapper.readTree("1"));
        registry.register(permission);

        assertThatThrownBy(() -> registry.register(permission("perm_2", objectMapper.readTree("2"))))
                .isInstanceOf(CodexPendingPermissionCapacityException.class);
        assertThat(permission.beginDecision(PermissionDecision.APPROVED, "cmd-1"))
                .isEqualTo(CodexPermissionDecisionAttempt.RESERVED);
        assertThat(permission.beginDecision(PermissionDecision.REJECTED, "cmd-2"))
                .isEqualTo(CodexPermissionDecisionAttempt.NOT_PENDING);
        permission.markDecisionSent("cmd-1");
        assertThat(permission.beginDecision(PermissionDecision.APPROVED, "cmd-1"))
                .isEqualTo(CodexPermissionDecisionAttempt.DUPLICATE);
    }

    private CodexPendingPermissionRegistry registry(int capacity) {
        AgentCodexProperties properties = new AgentCodexProperties();
        properties.setPendingPermissionCapacity(capacity);
        return new CodexPendingPermissionRegistry(properties);
    }

    private PendingPermission permission(String permissionId, JsonNode nativeId) {
        return new PendingPermission(permissionId, "ses-1", "cmd-prompt", "native-1", "turn-1", "item-1",
                "item/commandExecution/requestApproval", ".", nativeId, PermissionType.COMMAND_EXECUTION,
                "Command approval required", "reason",
                new CommandExecutionPermissionDetail("item-1", "turn-1", "git status", ".", "reason", null,
                        List.of(PermissionDecision.APPROVED, PermissionDecision.REJECTED), Map.of()),
                List.of(PermissionDecision.APPROVED, PermissionDecision.REJECTED), Instant.now(), Map.of());
    }
}
