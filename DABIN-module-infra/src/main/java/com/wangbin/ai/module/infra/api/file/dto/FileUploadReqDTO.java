package com.wangbin.ai.module.infra.api.file.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.InputStream;

/**
 * Internal file upload request for callers that cannot buffer large files in memory.
 */
@Data
public class FileUploadReqDTO {

    private Long configId;

    @NotBlank(message = "文件名不能为空")
    private String name;

    private String directory;

    private String type;

    @Min(value = 0, message = "文件大小不能小于 0")
    private long size;

    @NotNull(message = "文件流不能为空")
    private InputStream inputStream;
}
