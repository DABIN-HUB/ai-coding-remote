package com.wangbin.ai.module.agent.enums;

import com.wangbin.ai.framework.common.exception.ErrorCode;

public interface ErrorCodeConstants {

    ErrorCode PAIRING_CODE_NOT_EXISTS = new ErrorCode(1_020_000_001, "绑定码不存在或已过期");
    ErrorCode DEVICE_CREDENTIAL_INVALID = new ErrorCode(1_020_000_002, "设备凭证无效");
    ErrorCode DEVICE_CREDENTIAL_REVOKED = new ErrorCode(1_020_000_003, "设备凭证已吊销");
    ErrorCode DEVICE_CREDENTIAL_EXPIRED = new ErrorCode(1_020_000_004, "设备凭证已过期");
    ErrorCode DEVICE_NOT_EXISTS = new ErrorCode(1_020_000_005, "设备不存在");
    ErrorCode DEVICE_DISABLED = new ErrorCode(1_020_000_006, "设备已禁用");
    ErrorCode DEVICE_ACCESS_DENIED = new ErrorCode(1_020_000_007, "无设备访问权限");
    ErrorCode RELAY_TICKET_CREATE_FAILED = new ErrorCode(1_020_000_008, "Relay Ticket 创建失败");
    ErrorCode TENANT_ID_REQUIRED = new ErrorCode(1_020_000_009, "缺少租户编号");
}
