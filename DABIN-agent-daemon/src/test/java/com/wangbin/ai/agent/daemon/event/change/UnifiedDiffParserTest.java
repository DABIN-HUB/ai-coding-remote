package com.wangbin.ai.agent.daemon.event.change;

import com.wangbin.ai.agent.contract.enums.FileChangeType;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDiffParserTest {

    @Test
    void parsesMultipleFilesAndCountsAdditionsDeletions() throws Exception {
        Path workspace = workspace("parse-multi");
        UnifiedDiffParser parser = parser(workspace, properties());

        SanitizedDiff result = parser.parse(workspace, """
                diff --git a/src/Main.java b/src/Main.java
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -1,2 +1,3 @@
                 class Main {
                -  void old() {}
                +  void next() {}
                +  void added() {}
                 }
                diff --git a/docs/readme.md b/docs/readme.md
                --- /dev/null
                +++ b/docs/readme.md
                @@ -0,0 +1 @@
                +hello
                """);

        assertThat(result.fileCount()).isEqualTo(2);
        assertThat(result.additions()).isEqualTo(3);
        assertThat(result.deletions()).isEqualTo(1);
        assertThat(result.files().getFirst().changeType()).isEqualTo(FileChangeType.MODIFIED);
        assertThat(result.files().get(1).changeType()).isEqualTo(FileChangeType.ADDED);
        assertThat(result.diffSha256()).hasSize(64);
    }

    @Test
    void parsesDeletedRenamedBinaryAndSkipsTraversal() throws Exception {
        Path workspace = workspace("parse-types");
        UnifiedDiffParser parser = parser(workspace, properties());

        SanitizedDiff result = parser.parse(workspace, """
                diff --git a/old.txt b/new.txt
                rename from old.txt
                rename to new.txt
                --- a/old.txt
                +++ b/new.txt
                @@ -1 +1 @@
                -old
                +new
                diff --git a/remove.txt b/remove.txt
                --- a/remove.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -gone
                diff --git a/image.png b/image.png
                Binary files a/image.png and b/image.png differ
                diff --git a/../secret.txt b/../secret.txt
                --- a/../secret.txt
                +++ b/../secret.txt
                @@ -1 +1 @@
                -secret
                +secret2
                """);

        assertThat(result.files()).extracting(UnifiedDiffFile::path)
                .containsExactly("new.txt", "remove.txt", "image.png");
        assertThat(result.files().getFirst().oldPath()).isEqualTo("old.txt");
        assertThat(result.files().getFirst().changeType()).isEqualTo(FileChangeType.RENAMED);
        assertThat(result.files().get(1).changeType()).isEqualTo(FileChangeType.DELETED);
        assertThat(result.files().get(2).binary()).isTrue();
        assertThat(result.diff()).doesNotContain("secret2");
    }

    @Test
    void redactsSensitiveFilePatchFromFinalDiff() throws Exception {
        Path workspace = workspace("redact");
        UnifiedDiffParser parser = parser(workspace, properties());

        SanitizedDiff result = parser.parse(workspace, """
                diff --git a/.env.local b/.env.local
                --- a/.env.local
                +++ b/.env.local
                @@ -1 +1 @@
                -token=old
                +token=new
                diff --git a/src/Main.java b/src/Main.java
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -1 +1 @@
                -old
                +new
                """);

        assertThat(result.files().getFirst().redacted()).isTrue();
        assertThat(result.diff()).doesNotContain("token=old", "token=new");
        assertThat(result.diff()).contains("redacted sensitive file diff", "src/Main.java");
    }

    @Test
    void truncatesDiffAndFilesByConfiguration() throws Exception {
        Path workspace = workspace("truncate");
        AgentCodexProperties properties = properties();
        properties.setDiffSnapshotMaxChars(64);
        properties.setChangeSetMaxFiles(1);
        UnifiedDiffParser parser = parser(workspace, properties);

        SanitizedDiff result = parser.parse(workspace, """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                -aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                +bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
                diff --git a/b.txt b/b.txt
                --- a/b.txt
                +++ b/b.txt
                @@ -1 +1 @@
                -c
                +d
                """);

        assertThat(result.truncated()).isTrue();
        assertThat(result.filesTruncated()).isTrue();
        assertThat(result.files()).hasSize(1);
        assertThat(result.diff()).contains("diff truncated");
    }

    private UnifiedDiffParser parser(Path workspace, AgentCodexProperties properties) {
        WorkspaceManager workspaceManager = workspaceManager();
        WorkspaceRelativePathNormalizer normalizer = new WorkspaceRelativePathNormalizer(workspaceManager);
        return new UnifiedDiffParser(normalizer, new SensitivePathPolicy(), properties);
    }

    private AgentCodexProperties properties() {
        return new AgentCodexProperties();
    }

    private WorkspaceManager workspaceManager() {
        return new WorkspaceManager() {
            @Override
            public Path validateWorkspace(String workspacePath) {
                return Path.of(workspacePath).toAbsolutePath().normalize();
            }

            @Override
            public Path resolveWithinWorkspace(Path workspace, String relativePath) {
                Path resolved = workspace.resolve(relativePath).toAbsolutePath().normalize();
                if (!resolved.startsWith(workspace)) {
                    throw new AgentCapabilityException("path escapes workspace");
                }
                return resolved;
            }
        };
    }

    private Path workspace(String name) throws Exception {
        Path workspace = Path.of("target", "change-parser-test", name).toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        return workspace;
    }
}
