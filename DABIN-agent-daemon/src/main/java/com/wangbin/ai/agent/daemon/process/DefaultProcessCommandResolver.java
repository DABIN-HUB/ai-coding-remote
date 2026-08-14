package com.wangbin.ai.agent.daemon.process;

import com.wangbin.ai.agent.daemon.exception.AgentProcessException;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DefaultProcessCommandResolver implements ProcessCommandResolver {

    private static final List<String> WINDOWS_EXTENSIONS = List.of(".exe", ".cmd", ".bat", ".com", ".ps1");
    private static final List<String> WINDOWS_FALLBACK_ENV_DIRECTORIES = List.of("NVM_SYMLINK", "APPDATA");
    private static final String WINDOWS_NPM_DIRECTORY = "npm";

    @Override
    public ResolvedCommand resolve(String executable) {
        if (executable == null || executable.isBlank()) {
            throw new AgentProcessException("executable must not be blank");
        }
        Path configuredPath = Path.of(executable);
        if (configuredPath.isAbsolute() || configuredPath.getParent() != null) {
            return resolvedIfUsable(configuredPath)
                    .orElseThrow(() -> new AgentProcessException("executable does not exist: " + executable));
        }
        return searchPath(executable)
                .orElseThrow(() -> new AgentProcessException("executable not found on PATH: " + executable));
    }

    private java.util.Optional<ResolvedCommand> searchPath(String executable) {
        List<String> pathEntries = candidateDirectories();
        List<String> candidates = isWindows() && !hasExtension(executable)
                ? WINDOWS_EXTENSIONS.stream().map(extension -> executable + extension).toList()
                : List.of(executable);
        for (String pathEntry : pathEntries) {
            Path directory = Path.of(pathEntry);
            for (String candidate : candidates) {
                java.util.Optional<ResolvedCommand> command = resolvedIfUsable(directory.resolve(candidate));
                if (command.isPresent()) {
                    return command;
                }
            }
        }
        return java.util.Optional.empty();
    }

    private List<String> candidateDirectories() {
        List<String> pathEntries = new java.util.ArrayList<>(splitPath(environmentValue("PATH")));
        if (!isWindows()) {
            return pathEntries;
        }
        for (String environmentName : WINDOWS_FALLBACK_ENV_DIRECTORIES) {
            String value = environmentValue(environmentName);
            if (value == null || value.isBlank()) {
                continue;
            }
            Path directory = "APPDATA".equals(environmentName)
                    ? Path.of(value).resolve(WINDOWS_NPM_DIRECTORY)
                    : Path.of(value);
            pathEntries.add(directory.toString());
        }
        return pathEntries;
    }

    private java.util.Optional<ResolvedCommand> resolvedIfUsable(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            return java.util.Optional.empty();
        }
        String lowerName = normalized.getFileName().toString().toLowerCase(Locale.ROOT);
        if (isWindows() && lowerName.endsWith(".ps1")) {
            return java.util.Optional.of(new ResolvedCommand(normalized,
                    List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                            normalized.toString())));
        }
        if (isWindows() && (lowerName.endsWith(".cmd") || lowerName.endsWith(".bat"))) {
            return java.util.Optional.of(new ResolvedCommand(normalized,
                    List.of("cmd.exe", "/d", "/s", "/c", normalized.toString())));
        }
        return java.util.Optional.of(new ResolvedCommand(normalized, List.of(normalized.toString())));
    }

    private List<String> splitPath(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(java.io.File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .toList();
    }

    private String environmentValue(String name) {
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean hasExtension(String executable) {
        return executable.lastIndexOf('.') > executable.lastIndexOf(java.io.File.separatorChar);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

}
