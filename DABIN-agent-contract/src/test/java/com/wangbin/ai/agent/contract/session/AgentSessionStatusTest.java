package com.wangbin.ai.agent.contract.session;

import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSessionStatusTest {

    @Test
    void shouldRepresentPermissionAndUserWaitStatesExplicitly() {
        assertThat(EnumSet.allOf(AgentSessionStatus.class))
                .contains(AgentSessionStatus.WAITING_PERMISSION, AgentSessionStatus.WAITING_USER);
    }

}
