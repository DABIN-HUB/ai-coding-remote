package com.wangbin.ai.module.agent.service.artifact;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPageReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPrepareUploadReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPrepareUploadRespVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactReportFailureReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactRequestFileReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactRespVO;

import java.io.InputStream;
import java.io.OutputStream;

public interface AgentArtifactService {

    AgentArtifactRespVO requestFile(AgentArtifactRequestFileReqVO reqVO, Long userId);

    PageResult<AgentArtifactRespVO> getArtifactPage(AgentArtifactPageReqVO reqVO, Long userId);

    AgentArtifactRespVO getArtifact(String artifactId, Long userId);

    void download(String artifactId, Long userId, OutputStream outputStream) throws Exception;

    AgentArtifactPrepareUploadRespVO prepareUpload(Long tenantId, String credentialId, String credentialSecret,
                                                   AgentArtifactPrepareUploadReqVO reqVO);

    AgentArtifactRespVO upload(String uploadTicket, InputStream inputStream, long contentLength) throws Exception;

    void reportFailure(Long tenantId, String credentialId, String credentialSecret,
                       AgentArtifactReportFailureReqVO reqVO);

    int cleanupExpired();
}
