package com.wangbin.ai.agent.daemon.event.change;

import com.wangbin.ai.agent.contract.enums.*;
import com.wangbin.ai.agent.contract.event.*;
import com.wangbin.ai.agent.daemon.adapter.codex.CodexSessionContext;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentChangeSetAccumulatorTest {

    private static final String SESSION_ID = "ses-1";
    private static final String COMMAND_ID = "cmd-1";

    @Test
    void firstFileChangeCreatesChangeSetAndIdleFinalizesBeforeSessionIdle() throws Exception {
        Path workspace = workspace("idle-finalize");
        AgentChangeSetAccumulator accumulator = accumulator();
        CodexSessionContext context = context(workspace);

        List<AgentEvent> fileEvents = accumulator.accept(event(AgentEventType.FILE_CHANGED,
                new FileChangedPayload("src/App.java", null, FileChangeType.MODIFIED, "updated",
                        1, 0, false, false, false, Map.of())), context);
        List<AgentEvent> terminalEvents = accumulator.accept(event(AgentEventType.SESSION_IDLE,
                new SessionPayload("native-1", AgentSessionStatus.IDLE, null, Map.of())), context);

        FileChangedPayload changed = (FileChangedPayload) fileEvents.getFirst().payload();
        assertThat(changed.path()).isEqualTo("src/App.java");
        assertThat(changed.extensions()).containsKey("changeSetId");
        assertThat(terminalEvents).extracting(AgentEvent::type)
                .containsExactly(AgentEventType.CHANGE_SET_FINALIZED, AgentEventType.SESSION_IDLE);
        ChangeSetFinalizedPayload finalized = (ChangeSetFinalizedPayload) terminalEvents.getFirst().payload();
        assertThat(finalized.changeSetId()).isEqualTo(changed.extensions().get("changeSetId"));
        assertThat(finalized.status()).isEqualTo(ChangeSetStatus.COMPLETED);
        assertThat(finalized.files()).hasSize(1);
    }

    @Test
    void diffUpdateStoresLatestAndRedactsSensitivePatch() throws Exception {
        Path workspace = workspace("diff-finalize");
        AgentChangeSetAccumulator accumulator = accumulator();
        CodexSessionContext context = context(workspace);

        accumulator.accept(event(AgentEventType.DIFF_UPDATED, new DiffUpdatedPayload(null, """
                diff --git a/.env b/.env
                --- a/.env
                +++ b/.env
                @@ -1 +1 @@
                -password=old
                +password=new
                """, null, false, null, null, null, Map.of())), context);
        List<AgentEvent> terminalEvents = accumulator.accept(event(AgentEventType.SESSION_IDLE,
                new SessionPayload("native-1", AgentSessionStatus.IDLE, null, Map.of())), context);

        ChangeSetFinalizedPayload finalized = (ChangeSetFinalizedPayload) terminalEvents.getFirst().payload();
        assertThat(finalized.diff()).doesNotContain("password=old", "password=new");
        assertThat(finalized.files().getFirst().redacted()).isTrue();
        assertThat(finalized.diffSha256()).hasSize(64);
    }

    @Test
    void retryableErrorAndPermissionDoNotFinalizeButTerminalErrorDoes() throws Exception {
        Path workspace = workspace("terminal-error");
        AgentChangeSetAccumulator accumulator = accumulator();
        CodexSessionContext context = context(workspace);

        accumulator.accept(event(AgentEventType.FILE_CHANGED,
                new FileChangedPayload("src/App.java", null, FileChangeType.MODIFIED, null,
                        null, null, false, false, false, Map.of())), context);

        assertThat(accumulator.accept(event(AgentEventType.ERROR,
                new AgentErrorPayload("CODEX_RESPONSE_STREAM_DISCONNECTED", "retry", true, Map.of())), context))
                .extracting(AgentEvent::type)
                .containsExactly(AgentEventType.ERROR);
        assertThat(accumulator.accept(event(AgentEventType.PERMISSION_REQUIRED,
                new WarningPayload("not real payload", Map.of())), context))
                .extracting(AgentEvent::type)
                .containsExactly(AgentEventType.PERMISSION_REQUIRED);

        List<AgentEvent> terminalEvents = accumulator.accept(event(AgentEventType.ERROR,
                new AgentErrorPayload("CODEX_TURN_INTERRUPTED", "interrupted", false, Map.of())), context);

        assertThat(terminalEvents).extracting(AgentEvent::type)
                .containsExactly(AgentEventType.CHANGE_SET_FINALIZED, AgentEventType.ERROR);
        assertThat(((ChangeSetFinalizedPayload) terminalEvents.getFirst().payload()).status())
                .isEqualTo(ChangeSetStatus.INTERRUPTED);
    }

    @Test
    void differentSessionsAndCommandsAreIndependent() throws Exception {
        AgentChangeSetAccumulator accumulator = accumulator();
        CodexSessionContext first = context(workspace("session-1"));
        CodexSessionContext second = new CodexSessionContext("ses-2", "native-2", 1L, 11L, "dev-1", "prj-1",
                workspace("session-2").toString(), AgentType.CODEX);

        accumulator.accept(event(AgentEventType.FILE_CHANGED,
                new FileChangedPayload("a.txt", null, FileChangeType.MODIFIED, null,
                        null, null, false, false, false, Map.of())), first);
        accumulator.accept(event(AgentEventType.FILE_CHANGED,
                new FileChangedPayload("b.txt", null, FileChangeType.MODIFIED, null,
                        null, null, false, false, false, Map.of())), second);

        assertThat(accumulator.accept(event(AgentEventType.SESSION_IDLE,
                new SessionPayload("native-1", AgentSessionStatus.IDLE, null, Map.of())), first))
                .extracting(AgentEvent::type)
                .containsExactly(AgentEventType.CHANGE_SET_FINALIZED, AgentEventType.SESSION_IDLE);
        AgentEvent secondIdle = new AgentEvent(null, "trace-1", 1L, 11L, "dev-1", "prj-1", "ses-2", 0,
                AgentType.CODEX, AgentEventType.SESSION_IDLE, null, null,
                new SessionPayload("native-2", AgentSessionStatus.IDLE, null, Map.of()),
                Map.of(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, COMMAND_ID));
        assertThat(accumulator.accept(secondIdle, second))
                .extracting(AgentEvent::type)
                .containsExactly(AgentEventType.CHANGE_SET_FINALIZED, AgentEventType.SESSION_IDLE);
    }

    @Test
    void missingPlatformCommandIdDoesNotCreateChangeSet() throws Exception {
        AgentChangeSetAccumulator accumulator = accumulator();
        CodexSessionContext context = context(workspace("missing-command"));
        AgentEvent fileEvent = new AgentEvent(null, "trace-1", 1L, 11L, "dev-1", "prj-1", SESSION_ID, 0,
                AgentType.CODEX, AgentEventType.FILE_CHANGED, null, null,
                new FileChangedPayload("src/App.java", null, FileChangeType.MODIFIED, null,
                        null, null, false, false, false, Map.of()),
                Map.of());

        assertThat(accumulator.accept(fileEvent, context)).containsExactly(fileEvent);
        assertThat(accumulator.accept(event(AgentEventType.SESSION_IDLE,
                new SessionPayload("native-1", AgentSessionStatus.IDLE, null, Map.of())), context))
                .extracting(AgentEvent::type)
                .containsExactly(AgentEventType.SESSION_IDLE);
    }

    private AgentChangeSetAccumulator accumulator() {
        AgentCodexProperties properties = new AgentCodexProperties();
        WorkspaceRelativePathNormalizer normalizer = new WorkspaceRelativePathNormalizer(workspaceManager());
        SensitivePathPolicy sensitivePathPolicy = new SensitivePathPolicy();
        return new AgentChangeSetAccumulator(normalizer,
                new UnifiedDiffParser(normalizer, sensitivePathPolicy, properties),
                sensitivePathPolicy, new DaemonChangeSetIdFactory(), properties);
    }

    private AgentEvent event(AgentEventType type, AgentEventPayload payload) {
        return new AgentEvent(null, "trace-1", 1L, 11L, "dev-1", "prj-1", SESSION_ID, 0,
                AgentType.CODEX, type, null, null, payload,
                Map.of(AgentEventExtensionKeys.PLATFORM_COMMAND_ID, COMMAND_ID));
    }

    private CodexSessionContext context(Path workspace) {
        return new CodexSessionContext(SESSION_ID, "native-1", 1L, 11L, "dev-1", "prj-1",
                workspace.toString(), AgentType.CODEX);
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
        Path workspace = Path.of("target", "change-accumulator-test", name).toAbsolutePath().normalize();
        Files.createDirectories(workspace.resolve("src"));
        return workspace;
    }
}
