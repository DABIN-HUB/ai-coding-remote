package com.wangbin.ai.agent.daemon.process;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultProcessCommandResolverTest {

    @Test
    @SuppressWarnings("unchecked")
    void windowsCmdShouldUseCmdShellWrapper() throws Exception {
        Path executable = Files.createTempFile(Path.of("target").toAbsolutePath().normalize(),
                "codex-", ".cmd");
        String oldOsName = System.getProperty("os.name");
        System.setProperty("os.name", "Windows 11");
        try {
            DefaultProcessCommandResolver resolver = new DefaultProcessCommandResolver();
            Method method = DefaultProcessCommandResolver.class.getDeclaredMethod("resolvedIfUsable", Path.class);
            method.setAccessible(true);

            Optional<ResolvedCommand> command = (Optional<ResolvedCommand>) method.invoke(resolver, executable);

            assertThat(command).isPresent();
            assertThat(command.orElseThrow().command(java.util.List.of("--version")))
                    .containsExactly("cmd.exe", "/d", "/s", "/c", executable.toAbsolutePath().normalize().toString(),
                            "--version");
        } finally {
            System.setProperty("os.name", oldOsName);
        }
    }
}
