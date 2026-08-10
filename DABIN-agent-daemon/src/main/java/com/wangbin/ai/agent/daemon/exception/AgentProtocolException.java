package com.wangbin.ai.agent.daemon.exception;

public class AgentProtocolException extends RuntimeException {

    public AgentProtocolException(String message) {
        super(message);
    }

    public AgentProtocolException(String message, Throwable cause) {
        super(message, cause);
    }

}
