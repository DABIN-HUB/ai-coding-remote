package com.wangbin.ai.agent.contract.command;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "payloadType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PromptCommandPayload.class, name = "prompt"),
        @JsonSubTypes.Type(value = PermissionDecisionCommandPayload.class, name = "permissionDecision"),
        @JsonSubTypes.Type(value = ArtifactFetchCommandPayload.class, name = "artifactFetch")
})
public sealed interface AgentCommandPayload permits PromptCommandPayload, PermissionDecisionCommandPayload,
        ArtifactFetchCommandPayload {
}
