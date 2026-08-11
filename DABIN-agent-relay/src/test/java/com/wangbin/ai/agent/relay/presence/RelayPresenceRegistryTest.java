package com.wangbin.ai.agent.relay.presence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class RelayPresenceRegistryTest {

    @Test
    void compareDeleteScriptMatchesConnectionIdFieldExactly() throws Exception {
        Field field = RelayPresenceRegistry.class.getDeclaredField("COMPARE_DELETE_SCRIPT");
        field.setAccessible(true);

        String script = (String) field.get(null);

        assertThat(script).contains("cjson.decode");
        assertThat(script).contains("decoded.connectionId == ARGV[1]");
        assertThat(script).doesNotContain("string.find");
    }
}
