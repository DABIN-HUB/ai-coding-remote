package com.wangbin.ai.module.agent.controller.admin.message.vo;

import com.wangbin.ai.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - Agent Message 分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentMessagePageReqVO extends PageParam {
}
