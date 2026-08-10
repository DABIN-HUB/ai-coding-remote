package com.wangbin.ai.agent.contract.command;

import com.wangbin.ai.agent.contract.enums.CommandStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class CommandStatusTest {

    @Test
    void shouldSeparateTerminalAndNonTerminalStates() {
        EnumSet<CommandStatus> terminalStates = EnumSet.of(
                CommandStatus.COMPLETED,
                CommandStatus.FAILED,
                CommandStatus.REJECTED,
                CommandStatus.EXPIRED
        );

        assertThat(terminalStates).doesNotContain(CommandStatus.PENDING, CommandStatus.DISPATCHED,
                CommandStatus.DELIVERED, CommandStatus.ACCEPTED, CommandStatus.EXECUTING);
    }

}
