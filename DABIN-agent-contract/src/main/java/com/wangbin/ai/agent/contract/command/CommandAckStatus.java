package com.wangbin.ai.agent.contract.command;

/**
 * Transport-level acknowledgement state. It deliberately does not mean the
 * native Agent task has completed; final execution outcome is reported by
 * AgentEvent.
 */
public enum CommandAckStatus {

    RECEIVED,
    ACCEPTED,
    REJECTED,
    DUPLICATE,
    FAILED
}
