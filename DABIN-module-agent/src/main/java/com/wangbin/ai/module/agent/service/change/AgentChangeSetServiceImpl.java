package com.wangbin.ai.module.agent.service.change;

import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.ChangeSetFinalizedPayload;
import com.wangbin.ai.agent.contract.event.ChangedFileSummary;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.module.agent.controller.admin.change.vo.*;
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
import com.wangbin.ai.module.agent.framework.id.AgentIdFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static com.wangbin.ai.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.wangbin.ai.module.agent.enums.ErrorCodeConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentChangeSetServiceImpl implements AgentChangeSetService {

    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();
    private static final String INVALID_CHANGE_SET_ID = "invalid-change-set";

    private final AgentChangeSetMapper changeSetMapper;
    private final AgentFileChangeMapper fileChangeMapper;
    private final AgentSessionMapper sessionMapper;
    private final AgentCommandMapper commandMapper;
    private final AgentProjectMapper projectMapper;
    private final AgentIdFactory idFactory;

    @Override
    public void handleChangeSetFinalized(AgentSessionDO session, AgentEvent event, String platformCommandId,
                                         ChangeSetFinalizedPayload payload) {
        if (payload.changeSetId() == null || payload.changeSetId().isBlank()) {
            log.warn("reject ChangeSet finalized without changeSetId: sessionId={}, eventId={}",
                    event.sessionId(), event.eventId());
            return;
        }
        if (changeSetMapper.selectByChangeSetId(payload.changeSetId()) != null) {
            return;
        }
        AgentCommandDO command = platformCommandId == null ? null : commandMapper.selectByCommandId(platformCommandId);
        if (!isCommandValid(session, command)) {
            log.warn("reject ChangeSet finalized command mismatch: sessionId={}, eventId={}",
                    event.sessionId(), event.eventId());
            return;
        }
        AgentChangeSetDO changeSet = new AgentChangeSetDO();
        changeSet.setTenantId(session.getTenantId());
        changeSet.setChangeSetId(payload.changeSetId());
        changeSet.setSessionId(session.getId());
        changeSet.setCommandId(command.getId());
        changeSet.setDeviceId(session.getDeviceId());
        changeSet.setProjectId(session.getProjectId());
        changeSet.setOwnerUserId(session.getOwnerUserId());
        changeSet.setChangeStatus(payload.status().name());
        changeSet.setFileCount(payload.fileCount());
        changeSet.setAdditions(payload.additions());
        changeSet.setDeletions(payload.deletions());
        changeSet.setDiffText(payload.diff());
        changeSet.setDiffSha256(payload.diffSha256());
        changeSet.setDiffTruncated(payload.diffTruncated());
        changeSet.setFilesTruncated(payload.filesTruncated());
        changeSet.setStartedTime(LocalDateTime.now());
        changeSet.setCompletedTime(LocalDateTime.ofInstant(payload.completedAt(), SYSTEM_ZONE));
        changeSet.setRemark("");
        changeSetMapper.insert(changeSet);
        for (ChangedFileSummary file : payload.files()) {
            fileChangeMapper.insert(toFileChange(changeSet, file));
        }
    }

    @Override
    public PageResult<AgentChangeSetRespVO> getChangeSetPage(AgentChangeSetPageReqVO reqVO, Long userId) {
        Long sessionDbId = sessionDbId(reqVO.getSessionId(), userId);
        Long projectDbId = projectDbId(reqVO.getProjectId(), userId);
        Long commandDbId = commandDbId(reqVO.getCommandId(), userId);
        PageResult<AgentChangeSetDO> page = changeSetMapper.selectPage(reqVO, userId, sessionDbId, projectDbId,
                commandDbId);
        return new PageResult<>(page.getList().stream().map(this::toRespVO).toList(), page.getTotal());
    }

    @Override
    public AgentChangeSetDetailRespVO getChangeSet(String changeSetId, Long userId) {
        return toDetail(requireChangeSet(changeSetId, userId));
    }

    @Override
    public AgentChangeSetDetailRespVO getChangeSetByCommand(String commandId, Long userId) {
        AgentCommandDO command = requireCommand(commandId, userId);
        AgentChangeSetDO changeSet = changeSetMapper.selectByCommandId(command.getId());
        if (changeSet == null) {
            throw exception(CHANGE_SET_NOT_EXISTS);
        }
        if (!userId.equals(changeSet.getOwnerUserId())) {
            throw exception(CHANGE_SET_ACCESS_DENIED);
        }
        return toDetail(changeSet);
    }

    @Override
    public List<AgentFileChangeRespVO> getFileList(String changeSetId, Long userId) {
        AgentChangeSetDO changeSet = requireChangeSet(changeSetId, userId);
        return fileChangeMapper.selectListByChangeSetId(changeSet.getId()).stream()
                .map(file -> toFileRespVO(file, false))
                .toList();
    }

    @Override
    public AgentFileChangeRespVO getFileChange(String fileChangeId, Long userId) {
        AgentFileChangeDO file = fileChangeMapper.selectByFileChangeId(fileChangeId);
        if (file == null) {
            throw exception(FILE_CHANGE_NOT_EXISTS);
        }
        AgentChangeSetDO changeSet = changeSetMapper.selectById(file.getChangeSetId());
        if (changeSet == null || !userId.equals(changeSet.getOwnerUserId())) {
            throw exception(FILE_CHANGE_ACCESS_DENIED);
        }
        return toFileRespVO(file, true);
    }

    @Override
    public AgentChangeSetDiffRespVO getDiff(String changeSetId, Long userId) {
        AgentChangeSetDO changeSet = requireChangeSet(changeSetId, userId);
        AgentChangeSetDiffRespVO respVO = new AgentChangeSetDiffRespVO();
        respVO.setChangeSetId(changeSet.getChangeSetId());
        respVO.setDiffText(changeSet.getDiffText());
        respVO.setDiffSha256(changeSet.getDiffSha256());
        respVO.setDiffTruncated(changeSet.getDiffTruncated());
        return respVO;
    }

    private boolean isCommandValid(AgentSessionDO session, AgentCommandDO command) {
        return command != null
                && session.getId().equals(command.getSessionId())
                && session.getDeviceId().equals(command.getDeviceId())
                && session.getProjectId().equals(command.getProjectId())
                && session.getOwnerUserId().equals(command.getOwnerUserId())
                && session.getTenantId().equals(command.getTenantId());
    }

    private AgentFileChangeDO toFileChange(AgentChangeSetDO changeSet, ChangedFileSummary summary) {
        AgentFileChangeDO file = new AgentFileChangeDO();
        file.setTenantId(changeSet.getTenantId());
        file.setFileChangeId(idFactory.fileChangeId());
        file.setChangeSetId(changeSet.getId());
        file.setSessionId(changeSet.getSessionId());
        file.setCommandId(changeSet.getCommandId());
        file.setRelativePath(summary.path());
        file.setOldRelativePath(summary.oldPath());
        file.setChangeType(summary.changeType().name());
        file.setAdditions(summary.additions());
        file.setDeletions(summary.deletions());
        file.setBinaryFile(summary.binary());
        file.setPatchText(summary.patchText());
        file.setPatchSha256(summary.patchSha256());
        file.setPatchTruncated(summary.truncated());
        file.setRedacted(summary.redacted());
        file.setSummary("");
        return file;
    }

    private AgentChangeSetDetailRespVO toDetail(AgentChangeSetDO changeSet) {
        AgentChangeSetDetailRespVO respVO = new AgentChangeSetDetailRespVO();
        respVO.setChangeSet(toRespVO(changeSet));
        respVO.setFiles(fileChangeMapper.selectListByChangeSetId(changeSet.getId()).stream()
                .map(file -> toFileRespVO(file, false))
                .toList());
        return respVO;
    }

    private AgentChangeSetRespVO toRespVO(AgentChangeSetDO changeSet) {
        AgentChangeSetRespVO respVO = new AgentChangeSetRespVO();
        respVO.setId(changeSet.getId());
        respVO.setChangeSetId(changeSet.getChangeSetId());
        respVO.setSessionId(changeSet.getSessionId());
        respVO.setCommandId(changeSet.getCommandId());
        respVO.setProjectId(changeSet.getProjectId());
        respVO.setStatus(changeSet.getChangeStatus());
        respVO.setFileCount(changeSet.getFileCount());
        respVO.setAdditions(changeSet.getAdditions());
        respVO.setDeletions(changeSet.getDeletions());
        respVO.setDiffTruncated(changeSet.getDiffTruncated());
        respVO.setFilesTruncated(changeSet.getFilesTruncated());
        respVO.setStartedTime(changeSet.getStartedTime());
        respVO.setCompletedTime(changeSet.getCompletedTime());
        return respVO;
    }

    private AgentFileChangeRespVO toFileRespVO(AgentFileChangeDO file, boolean includePatch) {
        AgentFileChangeRespVO respVO = new AgentFileChangeRespVO();
        respVO.setId(file.getId());
        respVO.setFileChangeId(file.getFileChangeId());
        respVO.setRelativePath(file.getRelativePath());
        respVO.setOldRelativePath(file.getOldRelativePath());
        respVO.setChangeType(file.getChangeType());
        respVO.setAdditions(file.getAdditions());
        respVO.setDeletions(file.getDeletions());
        respVO.setBinary(file.getBinaryFile());
        respVO.setPatchTruncated(file.getPatchTruncated());
        respVO.setRedacted(file.getRedacted());
        respVO.setSummary(file.getSummary());
        respVO.setPatchSha256(file.getPatchSha256());
        if (includePatch) {
            respVO.setPatchText(file.getPatchText());
        }
        return respVO;
    }

    private AgentChangeSetDO requireChangeSet(String changeSetId, Long userId) {
        AgentChangeSetDO changeSet = changeSetMapper.selectByChangeSetId(changeSetId);
        if (changeSet == null) {
            throw exception(CHANGE_SET_NOT_EXISTS);
        }
        if (!userId.equals(changeSet.getOwnerUserId())) {
            throw exception(CHANGE_SET_ACCESS_DENIED);
        }
        return changeSet;
    }

    private AgentCommandDO requireCommand(String commandId, Long userId) {
        AgentCommandDO command = commandMapper.selectByCommandId(commandId);
        if (command == null) {
            throw exception(COMMAND_NOT_EXISTS);
        }
        if (!userId.equals(command.getOwnerUserId())) {
            throw exception(CHANGE_SET_ACCESS_DENIED);
        }
        return command;
    }

    private Long sessionDbId(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        AgentSessionDO session = sessionMapper.selectBySessionId(sessionId);
        if (session == null) {
            throw exception(SESSION_NOT_EXISTS);
        }
        if (!userId.equals(session.getOwnerUserId())) {
            throw exception(CHANGE_SET_ACCESS_DENIED);
        }
        return session.getId();
    }

    private Long projectDbId(String projectId, Long userId) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        AgentProjectDO project = projectMapper.selectByProjectId(projectId);
        if (project == null) {
            throw exception(PROJECT_NOT_EXISTS);
        }
        if (!userId.equals(project.getOwnerUserId())) {
            throw exception(CHANGE_SET_ACCESS_DENIED);
        }
        return project.getId();
    }

    private Long commandDbId(String commandId, Long userId) {
        if (commandId == null || commandId.isBlank()) {
            return null;
        }
        return requireCommand(commandId, userId).getId();
    }
}
