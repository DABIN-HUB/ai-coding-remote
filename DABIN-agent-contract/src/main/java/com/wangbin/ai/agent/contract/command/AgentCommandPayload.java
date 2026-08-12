package com.wangbin.ai.agent.contract.command;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "payloadType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PromptCommandPayload.class, name = "prompt"),
        @JsonSubTypes.Type(value = PermissionDecisionCommandPayload.class, name = "permissionDecision"),
        @JsonSubTypes.Type(value = ArtifactFetchCommandPayload.class, name = "artifactFetch"),
        @JsonSubTypes.Type(value = InterruptCommandPayload.class, name = "interrupt"),
        @JsonSubTypes.Type(value = CancelCommandPayload.class, name = "cancel"),
        @JsonSubTypes.Type(value = CloseSessionCommandPayload.class, name = "closeSession")
})
public sealed interface AgentCommandPayload permits PromptCommandPayload, PermissionDecisionCommandPayload,
        ArtifactFetchCommandPayload, InterruptCommandPayload, CancelCommandPayload, CloseSessionCommandPayload {
}
