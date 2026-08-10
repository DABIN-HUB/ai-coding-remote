package com.wangbin.ai.agent.daemon.exception;

public class AgentProcessException extends RuntimeException {

    public AgentProcessException(String message) {
        super(message);
    }

    public AgentProcessException(String message, Throwable cause) {
        super(message, cause);
    }

}
