package com.wangbin.ai.server.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPairingTenantIgnoreUrlTest {

    private static final Path SERVER_APPLICATION_YAML =
            Path.of("src", "main", "resources", "application.yaml");
    private static final String AGENT_PAIRING_URL = "- /admin-api/agent/device/pair";

    @Test
    void agentPairingEndpointDoesNotRequireTenantHeaderBeforePairingCodeValidation() throws Exception {
        String yaml = Files.readString(SERVER_APPLICATION_YAML);

        assertThat(yaml).contains(AGENT_PAIRING_URL);
    }

}
