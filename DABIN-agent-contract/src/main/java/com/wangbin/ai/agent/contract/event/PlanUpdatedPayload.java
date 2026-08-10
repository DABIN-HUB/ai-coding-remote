package com.wangbin.ai.agent.contract.event;

import java.util.List;
import java.util.Map;

public record PlanUpdatedPayload(
        String explanation,
        List<PlanStep> steps,
        Map<String, Object> extensions
) implements AgentEventPayload {

    public PlanUpdatedPayload {
        steps = steps == null ? List.of() : List.copyOf(steps);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

}
