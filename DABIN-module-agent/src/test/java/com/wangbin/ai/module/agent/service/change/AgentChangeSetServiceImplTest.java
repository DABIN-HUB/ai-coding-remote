package com.wangbin.ai.module.agent.service.change;

import com.wangbin.ai.agent.contract.enums.*;
import com.wangbin.ai.agent.contract.event.*;
import com.wangbin.ai.framework.common.exception.ServiceException;
import com.wangbin.ai.module.agent.controller.admin.change.vo.AgentChangeSetPageReqVO;
import com.wangbin.ai.module.agent.dal.dataobject.change.AgentChangeSetDO;
import com.wangbin.ai.module.agent.dal.dataobject.change.AgentFileChangeDO;
import com.wangbin.ai.module.agent.dal.dataobject.command.AgentCommandDO;
import com.wangbin.ai.module.agent.dal.dataobject.project.AgentProjectDO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;
import com.wangbin.ai.module.agent.dal.mysql.change.AgentChangeSetMapper;
import com.wangbin.ai.module.agent.dal.mysql.change.AgentFileChangeMapper;
import com.wangbin.ai.module.agent.dal.mysql.command.AgentCommandMapper;
import com.wangbin.ai.module.agent.dal.mysql.project.AgentProjectMapper;
import com.wangbin.ai.module.agent.dal.mysql.session.AgentSessionMapper;
import com.wangbin.ai.module.agent.enums.AgentCommandDbStatus;
import com.wangbin.ai.module.agent.enums.AgentSessionDbStatus;
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentChangeSetServiceImplTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long TEST_USER_ID = 11L;
    private static final Long OTHER_USER_ID = 12L;
    private static final Long TEST_DEVICE_DB_ID = 10L;
    private static final Long TEST_PROJECT_DB_ID = 20L;
    private static final Long TEST_SESSION_DB_ID = 30L;
    private static final Long TEST_COMMAND_DB_ID = 40L;
    private static final Long TEST_CHANGE_SET_DB_ID = 50L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_COMMAND_ID = "cmd-1";
    private static final String TEST_CHANGE_SET_ID = "chg-1";
    private static final String TEST_FILE_CHANGE_ID = "fchg-1";

    private final AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
    private final AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
    private final AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
    private final AgentCommandMapper commandMapper = mock(AgentCommandMapper.class);
    private final AgentProjectMapper projectMapper = mock(AgentProjectMapper.class);
    private final AgentIdFactory idFactory = mock(AgentIdFactory.class);
    private AgentChangeSetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentChangeSetServiceImpl(changeSetMapper, fileChangeMapper, sessionMapper, commandMapper,
                projectMapper, idFactory);
    }

    @Test
    void finalizedEventInsertsChangeSetAndFileChanges() {
        AgentSessionDO session = session();
        AgentCommandDO command = command(TEST_USER_ID);
        when(commandMapper.selectByCommandId(TEST_COMMAND_ID)).thenReturn(command);
        when(changeSetMapper.selectByChangeSetId(TEST_CHANGE_SET_ID)).thenReturn(null);
        when(idFactory.fileChangeId()).thenReturn(TEST_FILE_CHANGE_ID);
        doAnswer(invocation -> {
            AgentChangeSetDO changeSet = invocation.getArgument(0);
            changeSet.setId(TEST_CHANGE_SET_DB_ID);
            return 1;
        }).when(changeSetMapper).insert(any(AgentChangeSetDO.class));

        service.handleChangeSetFinalized(session, event(), TEST_COMMAND_ID, payload());

        ArgumentCaptor<AgentChangeSetDO> changeSetCaptor = ArgumentCaptor.forClass(AgentChangeSetDO.class);
        verify(changeSetMapper).insert(changeSetCaptor.capture());
        assertThat(changeSetCaptor.getValue().getChangeSetId()).isEqualTo(TEST_CHANGE_SET_ID);
        assertThat(changeSetCaptor.getValue().getCommandId()).isEqualTo(TEST_COMMAND_DB_ID);
        assertThat(changeSetCaptor.getValue().getDiffText()).contains("src/App.java");
        ArgumentCaptor<AgentFileChangeDO> fileCaptor = ArgumentCaptor.forClass(AgentFileChangeDO.class);
        verify(fileChangeMapper).insert(fileCaptor.capture());
        assertThat(fileCaptor.getValue().getRelativePath()).isEqualTo("src/App.java");
        assertThat(fileCaptor.getValue().getPatchText()).isEqualTo("@@");
        assertThat(fileCaptor.getValue().getRedacted()).isFalse();
    }

    @Test
    void duplicateFinalizedEventDoesNotInsertAgain() {
        when(changeSetMapper.selectByChangeSetId(TEST_CHANGE_SET_ID)).thenReturn(changeSet());

        service.handleChangeSetFinalized(session(), event(), TEST_COMMAND_ID, payload());

        verify(changeSetMapper, never()).insert(any(AgentChangeSetDO.class));
        verify(fileChangeMapper, never()).insert(any(AgentFileChangeDO.class));
    }

    @Test
    void commandMismatchRejectsFinalizationWithoutPersistence() {
        AgentCommandDO command = command(TEST_USER_ID);
        command.setSessionId(999L);
        when(commandMapper.selectByCommandId(TEST_COMMAND_ID)).thenReturn(command);

        service.handleChangeSetFinalized(session(), event(), TEST_COMMAND_ID, payload());

        verify(changeSetMapper, never()).insert(any(AgentChangeSetDO.class));
        verify(fileChangeMapper, never()).insert(any(AgentFileChangeDO.class));
    }

    @Test
    void getDetailAndDiffOmitLargeTextFromListResponses() {
        AgentChangeSetDO changeSet = changeSet();
        AgentFileChangeDO file = fileChange();
        when(changeSetMapper.selectByChangeSetId(TEST_CHANGE_SET_ID)).thenReturn(changeSet);
        when(changeSetMapper.selectById(TEST_CHANGE_SET_DB_ID)).thenReturn(changeSet);
        when(fileChangeMapper.selectListByChangeSetId(TEST_CHANGE_SET_DB_ID)).thenReturn(List.of(file));
        when(fileChangeMapper.selectByFileChangeId(TEST_FILE_CHANGE_ID)).thenReturn(file);

        var detail = service.getChangeSet(TEST_CHANGE_SET_ID, TEST_USER_ID);
        var fileList = service.getFileList(TEST_CHANGE_SET_ID, TEST_USER_ID);
        var fileDetail = service.getFileChange(TEST_FILE_CHANGE_ID, TEST_USER_ID);
        var diff = service.getDiff(TEST_CHANGE_SET_ID, TEST_USER_ID);

        assertThat(detail.getFiles().getFirst().getPatchText()).isNull();
        assertThat(fileList.getFirst().getPatchText()).isNull();
        assertThat(fileDetail.getPatchText()).isEqualTo("@@");
        assertThat(diff.getDiffText()).contains("src/App.java");
    }

    @Test
    void crossOwnerAccessIsDenied() {
        AgentChangeSetDO changeSet = changeSet();
        changeSet.setOwnerUserId(OTHER_USER_ID);
        when(changeSetMapper.selectByChangeSetId(TEST_CHANGE_SET_ID)).thenReturn(changeSet);

        assertThatThrownBy(() -> service.getChangeSet(TEST_CHANGE_SET_ID, TEST_USER_ID))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void pageResolvesBusinessFiltersToDbIds() {
        AgentChangeSetPageReqVO reqVO = new AgentChangeSetPageReqVO();
        reqVO.setSessionId(TEST_SESSION_ID);
        reqVO.setProjectId(TEST_PROJECT_ID);
        reqVO.setCommandId(TEST_COMMAND_ID);
        when(sessionMapper.selectBySessionId(TEST_SESSION_ID)).thenReturn(session());
        AgentProjectDO project = new AgentProjectDO();
        project.setId(TEST_PROJECT_DB_ID);
        project.setOwnerUserId(TEST_USER_ID);
        when(projectMapper.selectByProjectId(TEST_PROJECT_ID)).thenReturn(project);
        when(commandMapper.selectByCommandId(TEST_COMMAND_ID)).thenReturn(command(TEST_USER_ID));
        when(changeSetMapper.selectPage(eq(reqVO), eq(TEST_USER_ID), eq(TEST_SESSION_DB_ID), eq(TEST_PROJECT_DB_ID),
                eq(TEST_COMMAND_DB_ID))).thenReturn(new com.wangbin.ai.framework.common.pojo.PageResult<>(List.of(), 0L));

        var page = service.getChangeSetPage(reqVO, TEST_USER_ID);

        assertThat(page.getTotal()).isZero();
    }

    private ChangeSetFinalizedPayload payload() {
        return new ChangeSetFinalizedPayload(TEST_CHANGE_SET_ID, ChangeSetStatus.COMPLETED, 1, 2, 1,
                "diff --git a/src/App.java b/src/App.java\n", "sha", false, false,
                List.of(new ChangedFileSummary("src/App.java", null, FileChangeType.MODIFIED, 2, 1,
                        false, false, false, "@@", "patch-sha")),
                null, Map.of());
    }

    private AgentEvent event() {
        return AgentEvent.of("trace-1", TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID, TEST_PROJECT_ID,
                TEST_SESSION_ID, 1L, AgentType.CODEX, AgentEventType.CHANGE_SET_FINALIZED, payload());
    }

    private AgentSessionDO session() {
        AgentSessionDO session = new AgentSessionDO();
        session.setId(TEST_SESSION_DB_ID);
        session.setTenantId(TEST_TENANT_ID);
        session.setSessionId(TEST_SESSION_ID);
        session.setDeviceId(TEST_DEVICE_DB_ID);
        session.setProjectId(TEST_PROJECT_DB_ID);
        session.setOwnerUserId(TEST_USER_ID);
        session.setSessionStatus(AgentSessionDbStatus.RUNNING.name());
        return session;
    }

    private AgentCommandDO command(Long ownerUserId) {
        AgentCommandDO command = new AgentCommandDO();
        command.setId(TEST_COMMAND_DB_ID);
        command.setTenantId(TEST_TENANT_ID);
        command.setCommandId(TEST_COMMAND_ID);
        command.setSessionId(TEST_SESSION_DB_ID);
        command.setDeviceId(TEST_DEVICE_DB_ID);
        command.setProjectId(TEST_PROJECT_DB_ID);
        command.setOwnerUserId(ownerUserId);
        command.setCommandStatus(AgentCommandDbStatus.RUNNING.name());
        return command;
    }

    private AgentChangeSetDO changeSet() {
        AgentChangeSetDO changeSet = new AgentChangeSetDO();
        changeSet.setId(TEST_CHANGE_SET_DB_ID);
        changeSet.setTenantId(TEST_TENANT_ID);
        changeSet.setChangeSetId(TEST_CHANGE_SET_ID);
        changeSet.setSessionId(TEST_SESSION_DB_ID);
        changeSet.setCommandId(TEST_COMMAND_DB_ID);
        changeSet.setProjectId(TEST_PROJECT_DB_ID);
        changeSet.setOwnerUserId(TEST_USER_ID);
        changeSet.setChangeStatus(ChangeSetStatus.COMPLETED.name());
        changeSet.setFileCount(1);
        changeSet.setAdditions(2);
        changeSet.setDeletions(1);
        changeSet.setDiffText("diff --git a/src/App.java b/src/App.java\n");
        changeSet.setDiffSha256("sha");
        changeSet.setDiffTruncated(false);
        changeSet.setFilesTruncated(false);
        return changeSet;
    }

    private AgentFileChangeDO fileChange() {
        AgentFileChangeDO file = new AgentFileChangeDO();
        file.setId(60L);
        file.setTenantId(TEST_TENANT_ID);
        file.setFileChangeId(TEST_FILE_CHANGE_ID);
        file.setChangeSetId(TEST_CHANGE_SET_DB_ID);
        file.setSessionId(TEST_SESSION_DB_ID);
        file.setCommandId(TEST_COMMAND_DB_ID);
        file.setRelativePath("src/App.java");
        file.setChangeType(FileChangeType.MODIFIED.name());
        file.setAdditions(2);
        file.setDeletions(1);
        file.setBinaryFile(false);
        file.setPatchText("@@");
        file.setPatchSha256("patch-sha");
        file.setPatchTruncated(false);
        file.setRedacted(false);
        return file;
    }
}
