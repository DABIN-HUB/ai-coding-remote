package com.wangbin.ai.module.agent.controller.admin.permission.vo;

import com.wangbin.ai.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - Agent Permission 分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentPermissionPageReqVO extends PageParam {

    @Schema(description = "平台 Session 业务编号")
    private String sessionId;

    @Schema(description = "权限请求状态")
    private String permissionStatus;

    @Schema(description = "权限类型")
    private String permissionType;
}
