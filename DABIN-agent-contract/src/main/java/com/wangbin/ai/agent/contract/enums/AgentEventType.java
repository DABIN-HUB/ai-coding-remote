package com.wangbin.ai.agent.contract.enums;

public enum AgentEventType {

    SESSION_STARTED(EventPriority.CRITICAL),
    SESSION_STATE_CHANGED(EventPriority.IMPORTANT),
    SESSION_IDLE(EventPriority.NORMAL),
    SESSION_INTERRUPTED(EventPriority.CRITICAL),
    SESSION_COMPLETED(EventPriority.CRITICAL),

    AGENT_MESSAGE(EventPriority.IMPORTANT),
    AGENT_MESSAGE_DELTA(EventPriority.TRANSIENT),

    PLAN_UPDATED(EventPriority.IMPORTANT),

    TOOL_STARTED(EventPriority.IMPORTANT),
    TOOL_UPDATED(EventPriority.NORMAL),
    TOOL_COMPLETED(EventPriority.IMPORTANT),

    COMMAND_STARTED(EventPriority.IMPORTANT),
    COMMAND_OUTPUT(EventPriority.NORMAL),
    COMMAND_COMPLETED(EventPriority.IMPORTANT),

    FILE_CHANGED(EventPriority.NORMAL),
    DIFF_UPDATED(EventPriority.NORMAL),
    CHANGE_SET_FINALIZED(EventPriority.CRITICAL),

    PERMISSION_REQUIRED(EventPriority.CRITICAL),
    PERMISSION_RESOLVED(EventPriority.CRITICAL),

    TASK_COMPLETED(EventPriority.CRITICAL),

    ERROR(EventPriority.CRITICAL),
    WARNING(EventPriority.IMPORTANT);

    private final EventPriority defaultPriority;

    AgentEventType(EventPriority defaultPriority) {
        this.defaultPriority = defaultPriority;
    }

    public EventPriority defaultPriority() {
        return defaultPriority;
    }

}
