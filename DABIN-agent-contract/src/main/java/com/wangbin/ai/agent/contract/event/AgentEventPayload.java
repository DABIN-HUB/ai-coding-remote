package com.wangbin.ai.agent.contract.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "payloadType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SessionPayload.class, name = "session"),
        @JsonSubTypes.Type(value = AgentMessagePayload.class, name = "agentMessage"),
        @JsonSubTypes.Type(value = PlanUpdatedPayload.class, name = "planUpdated"),
        @JsonSubTypes.Type(value = ToolEventPayload.class, name = "toolEvent"),
        @JsonSubTypes.Type(value = CommandOutputPayload.class, name = "commandOutput"),
        @JsonSubTypes.Type(value = FileChangedPayload.class, name = "fileChanged"),
        @JsonSubTypes.Type(value = DiffUpdatedPayload.class, name = "diffUpdated"),
        @JsonSubTypes.Type(value = ChangeSetFinalizedPayload.class, name = "changeSetFinalized"),
        @JsonSubTypes.Type(value = PermissionRequiredPayload.class, name = "permissionRequired"),
        @JsonSubTypes.Type(value = PermissionResolvedPayload.class, name = "permissionResolved"),
        @JsonSubTypes.Type(value = TaskCompletedPayload.class, name = "taskCompleted"),
        @JsonSubTypes.Type(value = SessionInterruptedPayload.class, name = "sessionInterrupted"),
        @JsonSubTypes.Type(value = SessionControlTimeoutPayload.class, name = "sessionControlTimeout"),
        @JsonSubTypes.Type(value = AgentErrorPayload.class, name = "agentError"),
        @JsonSubTypes.Type(value = WarningPayload.class, name = "warning")
})
public sealed interface AgentEventPayload permits SessionPayload, AgentMessagePayload, PlanUpdatedPayload,
        ToolEventPayload, CommandOutputPayload, FileChangedPayload, DiffUpdatedPayload, ChangeSetFinalizedPayload,
        PermissionRequiredPayload, PermissionResolvedPayload, TaskCompletedPayload, SessionInterruptedPayload,
        SessionControlTimeoutPayload, AgentErrorPayload, WarningPayload {
}
