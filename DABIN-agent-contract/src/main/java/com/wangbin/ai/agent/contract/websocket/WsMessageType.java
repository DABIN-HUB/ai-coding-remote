package com.wangbin.ai.agent.contract.websocket;

public enum WsMessageType {
    HELLO,
    WELCOME,
    PING,
    PONG,
    AGENT_COMMAND,
    COMMAND_ACK,
    AGENT_EVENT,
    RUNTIME_REPORT,
    ERROR
}
