package com.wangbin.ai.agent.daemon.process;

import java.nio.file.Path;
import java.util.List;

public record ResolvedCommand(
        Path executablePath,
        List<String> commandPrefix
) {

    public ResolvedCommand {
        commandPrefix = List.copyOf(commandPrefix);
    }

    public List<String> command(List<String> arguments) {
        return java.util.stream.Stream.concat(commandPrefix.stream(), arguments.stream()).toList();
    }

}
