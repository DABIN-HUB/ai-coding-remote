package com.wangbin.ai.agent.daemon.project;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.wangbin.ai.agent.contract.enums.AgentType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizedProjectStoreTest {

    @Test
    void addProjectsShouldPersistMultipleProjectsAndReload() throws IOException {
        Path home = testHome("multi-");
        withUserHome(home, () -> {
            AuthorizedProjectStore store = store();
            store.addProjects(List.of(
                    new AuthorizedProjectState("local-a", "Project A", "target/project-a", AgentType.CODEX),
                    new AuthorizedProjectState("local-b", "Project B", "target/project-b", AgentType.CODEX)
            ));

            AuthorizedProjectStore reloaded = store();

            assertThat(reloaded.load())
                    .extracting(AuthorizedProjectState::localProjectId)
                    .containsExactly("local-a", "local-b");
        });
    }

    @Test
    void addProjectsShouldDeduplicateSameRealPath() throws IOException {
        Path home = testHome("dedup-");
        Path workspace = Path.of("target", "same-project").toAbsolutePath().normalize();
        withUserHome(home, () -> {
            AuthorizedProjectStore store = store();
            store.addProjects(List.of(new AuthorizedProjectState("local-a", "Project A",
                    workspace.toString(), AgentType.CODEX)));
            store.addProjects(List.of(new AuthorizedProjectState("local-b", "Project B",
                    workspace.toString(), AgentType.CODEX)));

            assertThat(store.load())
                    .singleElement()
                    .extracting(AuthorizedProjectState::localProjectId)
                    .isEqualTo("local-a");
        });
    }

    @Test
    void removeProjectsShouldDeleteByIdOrWorkspacePath() throws IOException {
        Path home = testHome("remove-");
        Path workspaceA = Path.of("target", "project-remove-a").toAbsolutePath().normalize();
        Path workspaceB = Path.of("target", "project-remove-b").toAbsolutePath().normalize();
        Path workspaceC = Path.of("target", "project-remove-c").toAbsolutePath().normalize();
        withUserHome(home, () -> {
            AuthorizedProjectStore store = store();
            store.addProjects(List.of(
                    new AuthorizedProjectState("local-a", "Project A", workspaceA.toString(), AgentType.CODEX),
                    new AuthorizedProjectState("local-b", "Project B", workspaceB.toString(), AgentType.CODEX),
                    new AuthorizedProjectState("local-c", "Project C", workspaceC.toString(), AgentType.CODEX)
            ));

            store.removeProjects(List.of("local-a", workspaceB.toString()));

            assertThat(store.load())
                    .singleElement()
                    .extracting(AuthorizedProjectState::localProjectId)
                    .isEqualTo("local-c");
        });
    }

    private Path testHome(String prefix) throws IOException {
        Path baseDir = Path.of("target", "authorized-project-store-test").toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        return Files.createTempDirectory(baseDir, prefix);
    }

    private AuthorizedProjectStore store() {
        return new AuthorizedProjectStore(JsonMapper.builder().build());
    }

    private void withUserHome(Path home, Runnable runnable) {
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            runnable.run();
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }
}
