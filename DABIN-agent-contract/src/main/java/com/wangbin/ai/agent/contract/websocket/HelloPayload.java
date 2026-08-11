package com.wangbin.ai.agent.contract.websocket;

public record HelloPayload(
        String protocolVersion,
        String relayTicket
) {
}
