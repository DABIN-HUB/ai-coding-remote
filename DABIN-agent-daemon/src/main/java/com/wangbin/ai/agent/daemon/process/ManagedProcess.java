package com.wangbin.ai.agent.daemon.process;

import com.wangbin.ai.agent.daemon.exception.AgentProcessException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ManagedProcess implements AutoCloseable {

    private final Process process;
    private final Instant startTime;
    private final AtomicReference<ProcessState> state;
    private final BufferedReader stdout;

    public ManagedProcess(Process process, AtomicReference<ProcessState> state) {
        this.process = process;
        this.state = state;
        this.startTime = Instant.now();
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    public long pid() {
        return process.pid();
    }

    public Instant startTime() {
        return startTime;
    }

    public ProcessState state() {
        return state.get();
    }

    public BufferedReader stdout() {
        return stdout;
    }

    public Process process() {
        return process;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public Optional<Integer> exitCode() {
        return process.isAlive() ? Optional.empty() : Optional.of(process.exitValue());
    }

    @Override
    public void close() {
        state.set(ProcessState.STOPPING);
        destroyProcessTree(false);
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                destroyProcessTree(true);
                process.waitFor(5, TimeUnit.SECONDS);
            }
            state.set(ProcessState.STOPPED);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentProcessException("interrupted while stopping process " + pid(), ex);
        }
    }

    void markExited(int exitCode) {
        state.compareAndSet(ProcessState.RUNNING, exitCode == 0 ? ProcessState.STOPPED : ProcessState.CRASHED);
    }

    private void destroyProcessTree(boolean forcibly) {
        process.descendants().forEach(handle -> {
            if (forcibly) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        });
        if (forcibly) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }
    }

}
