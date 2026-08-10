package com.wangbin.ai.agent.daemon.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.wangbin.ai.agent.daemon")
@ConfigurationPropertiesScan(basePackages = "com.wangbin.ai.agent.daemon")
public class DABINAgentDaemonApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DABINAgentDaemonApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }

}
