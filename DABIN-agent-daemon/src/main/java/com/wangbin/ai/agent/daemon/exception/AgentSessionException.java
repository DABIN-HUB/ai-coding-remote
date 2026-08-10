package com.wangbin.ai.agent.daemon.exception;

public class AgentSessionException extends RuntimeException {

    public AgentSessionException(String message) {
        super(message);
    }

    public AgentSessionException(String message, Throwable cause) {
        super(message, cause);
    }

}
