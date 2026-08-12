package com.wangbin.ai.module.agent.service.change;

import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.ChangeSetFinalizedPayload;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.module.agent.controller.admin.change.vo.AgentChangeSetDetailRespVO;
import com.wangbin.ai.module.agent.controller.admin.change.vo.AgentChangeSetDiffRespVO;
import com.wangbin.ai.module.agent.controller.admin.change.vo.AgentChangeSetPageReqVO;
import com.wangbin.ai.module.agent.controller.admin.change.vo.AgentChangeSetRespVO;
import com.wangbin.ai.module.agent.controller.admin.change.vo.AgentFileChangeRespVO;
import com.wangbin.ai.module.agent.dal.dataobject.session.AgentSessionDO;

import java.util.List;

public interface AgentChangeSetService {

    void handleChangeSetFinalized(AgentSessionDO session, AgentEvent event, String platformCommandId,
                                  ChangeSetFinalizedPayload payload);

    PageResult<AgentChangeSetRespVO> getChangeSetPage(AgentChangeSetPageReqVO reqVO, Long userId);

    AgentChangeSetDetailRespVO getChangeSet(String changeSetId, Long userId);

    AgentChangeSetDetailRespVO getChangeSetByCommand(String commandId, Long userId);

    List<AgentFileChangeRespVO> getFileList(String changeSetId, Long userId);

    AgentFileChangeRespVO getFileChange(String fileChangeId, Long userId);

    AgentChangeSetDiffRespVO getDiff(String changeSetId, Long userId);
}
