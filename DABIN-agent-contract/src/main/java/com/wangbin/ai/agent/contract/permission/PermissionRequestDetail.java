package com.wangbin.ai.agent.contract.permission;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "detailType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CommandExecutionPermissionDetail.class, name = "commandExecution"),
        @JsonSubTypes.Type(value = FileChangePermissionDetail.class, name = "fileChange"),
        @JsonSubTypes.Type(value = UnsupportedPermissionDetail.class, name = "unsupported")
})
public sealed interface PermissionRequestDetail permits CommandExecutionPermissionDetail,
        FileChangePermissionDetail, UnsupportedPermissionDetail {
}
