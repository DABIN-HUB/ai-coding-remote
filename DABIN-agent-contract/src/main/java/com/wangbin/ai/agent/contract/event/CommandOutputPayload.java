package com.wangbin.ai.agent.contract.event;

import java.util.Map;

public record CommandOutputPayload(
        String commandId,
        String stream,
        String output,
        boolean terminal,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public CommandOutputPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
