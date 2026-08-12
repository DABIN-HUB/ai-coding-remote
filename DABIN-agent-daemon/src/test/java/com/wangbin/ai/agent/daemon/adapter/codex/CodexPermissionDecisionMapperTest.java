package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodexPermissionDecisionMapperTest {

    private final CodexPermissionDecisionMapper mapper = new CodexPermissionDecisionMapper(new ObjectMapper());

    @Test
    void mapsPlatformDecisionsToCodexWireValues() {
        assertThat(mapper.responseResult(PermissionDecision.APPROVED).path("decision").asText())
                .isEqualTo("accept");
        assertThat(mapper.responseResult(PermissionDecision.APPROVED_FOR_SESSION).path("decision").asText())
                .isEqualTo("acceptForSession");
        assertThat(mapper.responseResult(PermissionDecision.REJECTED).path("decision").asText())
                .isEqualTo("decline");
        assertThat(mapper.responseResult(PermissionDecision.CANCELLED).path("decision").asText())
                .isEqualTo("cancel");
    }
}
