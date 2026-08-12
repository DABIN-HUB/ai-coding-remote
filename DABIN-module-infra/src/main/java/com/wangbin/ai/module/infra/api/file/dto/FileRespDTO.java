package com.wangbin.ai.module.infra.api.file.dto;

import lombok.Data;

@Data
public class FileRespDTO {

    private Long id;
    private Long configId;
    private String name;
    private String path;
    private String url;
    private String type;
    private Long size;
}
