package com.wangbin.ai.module.agent.framework.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentControlPlaneProperties.class, AgentArtifactProperties.class})
public class AgentModuleConfiguration {
}
