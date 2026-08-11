package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneResponseDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registerProjectResponseShouldIgnoreDisplayOnlyFields() throws Exception {
        JavaType resultType = objectMapper.getTypeFactory()
                .constructParametricType(ControlPlaneResult.class, RegisterProjectResponse.class);

        ControlPlaneResult<RegisterProjectResponse> result = objectMapper.readValue("""
                {
                  "code": 0,
                  "msg": "",
                  "traceId": "trace-smoke",
                  "data": {
                    "id": 100,
                    "projectId": "prj_smoke",
                    "localProjectId": "local_smoke",
                    "projectName": "smoke",
                    "workspacePath": "display-only",
                    "workspaceRealPath": "display-only-real",
                    "agentType": "CODEX",
                    "deviceId": "dev_display_only"
                  }
                }
                """, resultType);

        assertThat(result.code()).isZero();
        assertThat(result.data().projectId()).isEqualTo("prj_smoke");
        assertThat(result.data().localProjectId()).isEqualTo("local_smoke");
    }
}
