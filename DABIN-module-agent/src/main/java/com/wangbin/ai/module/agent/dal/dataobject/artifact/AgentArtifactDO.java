package com.wangbin.ai.module.agent.dal.dataobject.artifact;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wangbin.ai.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("ai_code_artifact")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentArtifactDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String artifactId;
    private String artifactSourceType;
    private String artifactStatus;
    private Long sessionId;
    private Long sourceCommandId;
    private Long transferCommandId;
    private Long changeSetId;
    private Long fileChangeId;
    private Long deviceId;
    private Long projectId;
    private Long ownerUserId;
    private String relativePath;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String sha256;
    private Long fileId;
    private String clientRequestId;
    private LocalDateTime sourceLastModifiedTime;
    private LocalDateTime requestedTime;
    private LocalDateTime uploadStartedTime;
    private LocalDateTime readyTime;
    private LocalDateTime expireTime;
    private String errorCode;
    private String errorMessage;
}
