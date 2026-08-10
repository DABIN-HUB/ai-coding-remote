package com.wangbin.ai.agent.contract.session;

import java.util.Map;
import java.util.UUID;

public record PromptCommand(
        String commandId,
        String prompt,
        Map<String, Object> extensions
) {

    public PromptCommand {
        commandId = commandId == null || commandId.isBlank() ? UUID.randomUUID().toString() : commandId;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
