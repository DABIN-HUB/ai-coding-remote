package com.wangbin.ai.agent.daemon.exception;

public class AgentCapabilityException extends RuntimeException {

    public AgentCapabilityException(String message) {
        super(message);
    }

    public AgentCapabilityException(String message, Throwable cause) {
        super(message, cause);
    }

}
