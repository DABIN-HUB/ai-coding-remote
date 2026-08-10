package com.wangbin.ai.agent.daemon.runtime;

import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import com.wangbin.ai.agent.daemon.process.ProcessCommandResolver;
import com.wangbin.ai.agent.daemon.process.ResolvedCommand;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class DefaultRuntimeDiscovery implements RuntimeDiscovery {

    private final AgentCodexProperties codexProperties;
    private final ProcessCommandResolver commandResolver;

    public DefaultRuntimeDiscovery(AgentCodexProperties codexProperties, ProcessCommandResolver commandResolver) {
        this.codexProperties = codexProperties;
        this.commandResolver = commandResolver;
    }

    @Override
    public RuntimeDiscoveryResult discover(AgentType agentType) {
        if (agentType == AgentType.CODEX) {
            return discoverCodex();
        }
        return new RuntimeDiscoveryResult(agentType, RuntimeInstallStatus.NOT_INSTALLED, null, null, null,
                "runtime discovery is not implemented for " + agentType, Map.of());
    }

    private RuntimeDiscoveryResult discoverCodex() {
        String executable = codexProperties.getExecutable();
        try {
            ResolvedCommand command = commandResolver.resolve(executable);
            Process process = new ProcessBuilder(command.command(List.of("--version"))).redirectErrorStream(true).start();
            boolean exited = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                return new RuntimeDiscoveryResult(AgentType.CODEX, RuntimeInstallStatus.UNKNOWN, executable,
                        null, command.executablePath(), "codex --version timed out", Map.of());
            }
            String version;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                version = reader.readLine();
            }
            if (process.exitValue() != 0) {
                return new RuntimeDiscoveryResult(AgentType.CODEX, RuntimeInstallStatus.NOT_INSTALLED, executable,
                        null, command.executablePath(), version, Map.of("exitCode", process.exitValue()));
            }
            return new RuntimeDiscoveryResult(AgentType.CODEX, RuntimeInstallStatus.INSTALLED, executable,
                    version, command.executablePath(), null, Map.of("exitCode", process.exitValue()));
        } catch (Exception ex) {
            return new RuntimeDiscoveryResult(AgentType.CODEX, RuntimeInstallStatus.NOT_INSTALLED, executable,
                    null, null, ex.getMessage(), Map.of());
        }
    }

}
