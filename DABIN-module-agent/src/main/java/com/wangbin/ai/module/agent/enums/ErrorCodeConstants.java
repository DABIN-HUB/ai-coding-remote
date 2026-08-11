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
    ErrorCode PAIRING_CODE_CREATE_FAILED = new ErrorCode(1_020_000_010, "Pairing code create failed");
    ErrorCode PAIRING_CODE_CONSUME_FAILED = new ErrorCode(1_020_000_011, "Pairing code consume failed");
    ErrorCode PAIRING_CONCURRENT_CONFLICT = new ErrorCode(1_020_000_012, "Device pairing is busy");
    ErrorCode PROJECT_NOT_EXISTS = new ErrorCode(1_020_000_013, "项目不存在");
    ErrorCode PROJECT_ACCESS_DENIED = new ErrorCode(1_020_000_014, "无项目访问权限");
    ErrorCode PROJECT_DISABLED = new ErrorCode(1_020_000_015, "项目已禁用");
    ErrorCode PROJECT_DEVICE_MISMATCH = new ErrorCode(1_020_000_016, "项目与设备不匹配");
    ErrorCode PROJECT_WORKSPACE_INVALID = new ErrorCode(1_020_000_017, "项目工作目录无效");
    ErrorCode RUNTIME_NOT_EXISTS = new ErrorCode(1_020_000_018, "Runtime 不存在");
    ErrorCode RUNTIME_UNAVAILABLE = new ErrorCode(1_020_000_019, "Runtime 不可用");
    ErrorCode SESSION_NOT_EXISTS = new ErrorCode(1_020_000_020, "Session 不存在");
    ErrorCode SESSION_ACCESS_DENIED = new ErrorCode(1_020_000_021, "无 Session 访问权限");
    ErrorCode SESSION_CLOSED = new ErrorCode(1_020_000_022, "Session 已关闭");
    ErrorCode SESSION_STATE_INVALID = new ErrorCode(1_020_000_023, "Session 状态不允许当前操作");
    ErrorCode COMMAND_NOT_EXISTS = new ErrorCode(1_020_000_024, "Command 不存在");
    ErrorCode COMMAND_STATE_INVALID = new ErrorCode(1_020_000_025, "Command 状态非法");
    ErrorCode COMMAND_DUPLICATE_REQUEST = new ErrorCode(1_020_000_026, "重复的 Command 请求");
    ErrorCode DEVICE_OFFLINE = new ErrorCode(1_020_000_027, "设备不在线");
    ErrorCode DEVICE_ROUTE_NOT_FOUND = new ErrorCode(1_020_000_028, "设备路由不存在");
    ErrorCode COMMAND_DISPATCH_FAILED = new ErrorCode(1_020_000_029, "Command 投递失败");
    ErrorCode COMMAND_ACK_TIMEOUT = new ErrorCode(1_020_000_030, "Command ACK 超时");
    ErrorCode AGENT_EVENT_SEQUENCE_GAP = new ErrorCode(1_020_000_031, "AgentEvent 序号存在缺口");
}
