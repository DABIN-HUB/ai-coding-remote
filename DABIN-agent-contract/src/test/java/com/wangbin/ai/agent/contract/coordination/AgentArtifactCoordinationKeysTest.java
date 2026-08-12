package com.wangbin.ai.agent.contract.coordination;

import com.wangbin.ai.agent.contract.protocol.AgentHttpHeaders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentArtifactCoordinationKeysTest {

    @Test
    void artifactUploadTicketKeyDoesNotExposeRawTicket() {
        String rawTicket = "raw-ticket-secret-value";

        String key = AgentCoordinationKeys.artifactUploadTicket(rawTicket);

        assertThat(key).startsWith("agent:artifact:upload:");
        assertThat(key).doesNotContain(rawTicket);
    }

    @Test
    void artifactUploadTicketHeaderIsCentralized() {
        assertThat(AgentHttpHeaders.ARTIFACT_UPLOAD_TICKET)
                .isEqualTo("X-Agent-Artifact-Upload-Ticket");
    }

    @Test
    void artifactRequestLockHashesClientControlledIds() {
        String key = AgentCoordinationKeys.artifactRequestLock(1L, 2L,
                "fchg-sensitive-path", "client-visible-request-id");

        assertThat(key).startsWith("agent:lock:artifact:request:1:2:");
        assertThat(key).doesNotContain("fchg-sensitive-path");
        assertThat(key).doesNotContain("client-visible-request-id");
    }
}
