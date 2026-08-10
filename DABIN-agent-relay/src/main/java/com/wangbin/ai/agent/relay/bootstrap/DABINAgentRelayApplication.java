package com.wangbin.ai.agent.relay.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.wangbin.ai.agent.relay")
@ConfigurationPropertiesScan(basePackages = "com.wangbin.ai.agent.relay")
public class DABINAgentRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(DABINAgentRelayApplication.class, args);
    }

}
