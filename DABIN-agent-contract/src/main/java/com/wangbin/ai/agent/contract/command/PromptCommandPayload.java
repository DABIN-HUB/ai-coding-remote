package com.wangbin.ai.agent.contract.command;

import java.util.Map;

public record PromptCommandPayload(
        String prompt,
        Map<String, Object> extensions
) implements AgentCommandPayload {

    public PromptCommandPayload {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
