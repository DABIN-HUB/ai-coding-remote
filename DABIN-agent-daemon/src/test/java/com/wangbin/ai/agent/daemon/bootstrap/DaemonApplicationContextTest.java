package com.wangbin.ai.agent.daemon.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonApplicationContextTest {

    @Test
    void daemonApplicationContextStartsWithoutCloudMode() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DABINAgentDaemonApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.web-application-type=none")
                .run()) {
            assertThat(context.isActive()).isTrue();
        }
    }

}
