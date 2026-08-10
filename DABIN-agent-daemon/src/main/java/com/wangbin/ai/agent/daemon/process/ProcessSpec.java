package com.wangbin.ai.agent.daemon.process;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record ProcessSpec(
        List<String> command,
        Path cwd,
        Map<String, String> environment
) {

    public ProcessSpec {
        command = List.copyOf(command);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

}
