package com.wangbin.ai.module.agent.controller.admin.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Daemon - Agent 设备绑定 Response VO")
@Data
public class AgentDevicePairRespVO {

    @Schema(description = "租户编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long tenantId;

    @Schema(description = "设备业务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String deviceId;

    @Schema(description = "设备凭证公开编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String credentialId;

    @Schema(description = "设备凭证明文密钥，仅绑定成功时首次返回，服务端不会保存明文", requiredMode = Schema.RequiredMode.REQUIRED)
    private String credentialSecret;
}
