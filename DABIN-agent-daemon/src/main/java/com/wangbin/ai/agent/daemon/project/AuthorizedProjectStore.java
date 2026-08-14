package com.wangbin.ai.agent.daemon.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AuthorizedProjectStore {

    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String DAEMON_BASE_DIR = ".agent-remote";
    private static final String STATE_DIR = "state";
    private static final String PROJECTS_FILE = "projects.json";
    private static final TypeReference<List<AuthorizedProjectState>> PROJECT_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path projectsPath;

    public AuthorizedProjectStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.projectsPath = Path.of(System.getProperty(USER_HOME_PROPERTY), DAEMON_BASE_DIR, STATE_DIR, PROJECTS_FILE);
    }

    public synchronized List<AuthorizedProjectState> load() {
        try {
            if (!Files.exists(projectsPath)) {
                return List.of();
            }
            List<AuthorizedProjectState> projects = objectMapper.readValue(
                    Files.readString(projectsPath, StandardCharsets.UTF_8), PROJECT_LIST_TYPE);
            return projects == null ? List.of() : List.copyOf(projects);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load authorized project state", ex);
        }
    }

    public synchronized void save(List<AuthorizedProjectState> projects) {
        try {
            Files.createDirectories(projectsPath.getParent());
            writeSecure(projectsPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(projects));
        } catch (IOException ex) {
            throw new IllegalStateException("failed to save authorized project state", ex);
        }
    }

    public synchronized List<AuthorizedProjectState> addProjects(Collection<AuthorizedProjectState> projects) {
        Map<String, AuthorizedProjectState> merged = new LinkedHashMap<>();
        for (AuthorizedProjectState existing : load()) {
            merged.put(normalizedWorkspaceKey(existing.workspacePath()), existing);
        }
        for (AuthorizedProjectState project : projects) {
            merged.putIfAbsent(normalizedWorkspaceKey(project.workspacePath()), project);
        }
        List<AuthorizedProjectState> result = new ArrayList<>(merged.values());
        save(result);
        return List.copyOf(result);
    }

    public synchronized List<AuthorizedProjectState> removeProjects(Collection<String> selectors) {
        Set<String> rawSelectors = selectors.stream()
                .filter(selector -> selector != null && !selector.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> workspaceSelectors = rawSelectors.stream()
                .map(this::normalizedWorkspaceKey)
                .collect(java.util.stream.Collectors.toSet());
        List<AuthorizedProjectState> result = load().stream()
                .filter(project -> !rawSelectors.contains(project.localProjectId()))
                .filter(project -> !workspaceSelectors.contains(normalizedWorkspaceKey(project.workspacePath())))
                .toList();
        save(result);
        return List.copyOf(result);
    }

    private String normalizedWorkspaceKey(String workspacePath) {
        return Path.of(workspacePath).toAbsolutePath().normalize().toString();
    }

    private void writeSecure(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows uses the user's profile ACL. Project state contains no credential secret.
        }
    }
}
