package com.wangbin.ai.agent.daemon.adapter.codex.protocol;

import java.util.Set;

public final class CodexProtocolConstants {

    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_INITIALIZED = "initialized";
    public static final String METHOD_THREAD_START = "thread/start";
    public static final String METHOD_TURN_START = "turn/start";
    public static final String METHOD_TURN_INTERRUPT = "turn/interrupt";
    public static final String METHOD_THREAD_STARTED = "thread/started";
    public static final String METHOD_THREAD_STATUS_CHANGED = "thread/status/changed";
    public static final String METHOD_TURN_STARTED = "turn/started";
    public static final String METHOD_TURN_COMPLETED = "turn/completed";
    public static final String METHOD_ITEM_STARTED = "item/started";
    public static final String METHOD_ITEM_COMPLETED = "item/completed";
    public static final String METHOD_AGENT_MESSAGE_DELTA = "item/agentMessage/delta";
    public static final String METHOD_PLAN_DELTA = "item/plan/delta";
    public static final String METHOD_PLAN_UPDATED = "turn/plan/updated";
    public static final String METHOD_COMMAND_OUTPUT_DELTA = "item/commandExecution/outputDelta";
    public static final String METHOD_COMMAND_TERMINAL_INTERACTION = "item/commandExecution/terminalInteraction";
    public static final String METHOD_COMMAND_EXEC_OUTPUT_DELTA = "command/exec/outputDelta";
    public static final String METHOD_FILE_CHANGE_OUTPUT_DELTA = "item/fileChange/outputDelta";
    public static final String METHOD_FILE_CHANGE_PATCH_UPDATED = "item/fileChange/patchUpdated";
    public static final String METHOD_DIFF_UPDATED = "turn/diff/updated";
    public static final String METHOD_ERROR = "error";
    public static final String METHOD_WARNING = "warning";
    public static final String METHOD_GUARDIAN_WARNING = "guardianWarning";
    public static final String METHOD_CONFIG_WARNING = "configWarning";
    public static final String METHOD_PERMISSION_REQUEST_APPROVAL = "item/permissions/requestApproval";
    public static final String METHOD_COMMAND_REQUEST_APPROVAL = "item/commandExecution/requestApproval";
    public static final String METHOD_FILE_CHANGE_REQUEST_APPROVAL = "item/fileChange/requestApproval";
    public static final String METHOD_LEGACY_APPLY_PATCH_APPROVAL = "applyPatchApproval";
    public static final String METHOD_LEGACY_EXEC_COMMAND_APPROVAL = "execCommandApproval";
    public static final String ITEM_TYPE_AGENT_MESSAGE = "agentMessage";
    public static final String ITEM_TYPE_COMMAND_EXECUTION = "commandExecution";
    public static final String ITEM_TYPE_FILE_CHANGE = "fileChange";
    public static final String ITEM_TYPE_PLAN = "plan";
    public static final String MESSAGE_PHASE_COMMENTARY = "commentary";
    public static final String MESSAGE_PHASE_FINAL_ANSWER = "final_answer";
    public static final String TURN_STATUS_COMPLETED = "completed";
    public static final String TURN_STATUS_INTERRUPTED = "interrupted";
    public static final String TURN_STATUS_FAILED = "failed";
    public static final int JSON_RPC_METHOD_NOT_FOUND = -32601;
    public static final int JSON_RPC_ROUTE_UNAVAILABLE = -32000;

    public static final Set<String> APPROVAL_REQUEST_METHODS = Set.of(
            METHOD_PERMISSION_REQUEST_APPROVAL,
            METHOD_COMMAND_REQUEST_APPROVAL,
            METHOD_FILE_CHANGE_REQUEST_APPROVAL,
            METHOD_LEGACY_APPLY_PATCH_APPROVAL,
            METHOD_LEGACY_EXEC_COMMAND_APPROVAL
    );

    private CodexProtocolConstants() {
    }

}
