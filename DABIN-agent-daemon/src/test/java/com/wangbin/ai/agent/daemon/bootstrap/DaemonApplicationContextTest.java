package com.wangbin.ai.agent.daemon.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonApplicationContextTest {

    @Test
    void daemonApplicationContextStartsWithoutCloudMode() {
        Path tempHome = Path.of("target", "daemon-context-test-home").toAbsolutePath().normalize();
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DABINAgentDaemonApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties("spring.main.web-application-type=none")
                    .run()) {
                assertThat(context.isActive()).isTrue();
            }
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

}
