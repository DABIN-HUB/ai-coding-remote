package com.wangbin.ai.agent.daemon.adapter.codex;

import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import com.wangbin.ai.agent.daemon.process.ManagedProcess;
import com.wangbin.ai.agent.daemon.process.ProcessCommandResolver;
import com.wangbin.ai.agent.daemon.process.ProcessSpec;
import com.wangbin.ai.agent.daemon.process.ProcessSupervisor;
import com.wangbin.ai.agent.daemon.process.ResolvedCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class CodexAppServerProcess {

    private static final Logger log = LoggerFactory.getLogger(CodexAppServerProcess.class);

    private final AgentCodexProperties properties;
    private final ProcessSupervisor processSupervisor;
    private final ProcessCommandResolver commandResolver;

    public CodexAppServerProcess(AgentCodexProperties properties, ProcessSupervisor processSupervisor,
                                 ProcessCommandResolver commandResolver) {
        this.properties = properties;
        this.processSupervisor = processSupervisor;
        this.commandResolver = commandResolver;
    }

    public ManagedProcess start(Path cwd) {
        ResolvedCommand command = commandResolver.resolve(properties.getExecutable());
        ProcessSpec spec = new ProcessSpec(command.command(List.of("app-server", "--stdio")),
                cwd, Map.of());
        return processSupervisor.start(spec, line -> log.debug("codex stderr: {}", line));
    }

}
