package com.wangbin.ai.module.infra.api.file.dto;

import lombok.Data;

/**
 * File client capability visible to other modules without exposing provider implementation classes.
 */
@Data
public class FileClientCapabilityRespDTO {

    private Long configId;

    private Boolean streamingUpload;

    private Boolean streamingDownload;
}
