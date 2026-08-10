package com.wangbin.ai.agent.daemon.process;

import com.wangbin.ai.agent.daemon.exception.AgentProcessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class DefaultProcessSupervisor implements ProcessSupervisor {

    private final ExecutorService processIoExecutor;

    public DefaultProcessSupervisor(@Qualifier("agentProcessIoExecutor") ExecutorService processIoExecutor) {
        this.processIoExecutor = processIoExecutor;
    }

    @Override
    public ManagedProcess start(ProcessSpec spec, Consumer<String> stderrConsumer) {
        try {
            AtomicReference<ProcessState> state = new AtomicReference<>(ProcessState.STARTING);
            ProcessBuilder builder = new ProcessBuilder(spec.command());
            if (spec.cwd() != null) {
                builder.directory(spec.cwd().toFile());
            }
            builder.environment().putAll(spec.environment());
            builder.redirectErrorStream(false);
            Process process = builder.start();
            ManagedProcess managedProcess = new ManagedProcess(process, state);
            state.set(ProcessState.RUNNING);
            consumeStderr(process, stderrConsumer);
            watchExit(managedProcess);
            return managedProcess;
        } catch (Exception ex) {
            throw new AgentProcessException("failed to start process: " + spec.command(), ex);
        }
    }

    private void consumeStderr(Process process, Consumer<String> stderrConsumer) {
        processIoExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrConsumer.accept(line);
                }
            } catch (Exception ex) {
                stderrConsumer.accept("stderr reader failed: " + ex.getMessage());
            }
        });
    }

    private void watchExit(ManagedProcess managedProcess) {
        processIoExecutor.submit(() -> {
            try {
                int exitCode = managedProcess.process().waitFor();
                managedProcess.markExited(exitCode);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }

}
