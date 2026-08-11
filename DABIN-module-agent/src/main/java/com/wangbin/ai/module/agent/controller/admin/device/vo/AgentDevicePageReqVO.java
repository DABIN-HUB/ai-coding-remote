package com.wangbin.ai.module.agent.controller.admin.device.vo;

import com.wangbin.ai.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - Agent 设备分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentDevicePageReqVO extends PageParam {

    @Schema(description = "设备名称", example = "MacBook Pro")
    private String deviceName;

    @Schema(description = "设备注册状态，ACTIVE=正常，DISABLED=禁用", example = "ACTIVE")
    private String deviceStatus;
}
