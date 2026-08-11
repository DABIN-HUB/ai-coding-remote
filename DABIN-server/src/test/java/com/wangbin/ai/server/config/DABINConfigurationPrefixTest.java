package com.wangbin.ai.server.config;

import com.wangbin.ai.framework.redis.config.DABINCacheProperties;
import com.wangbin.ai.framework.security.config.SecurityProperties;
import com.wangbin.ai.framework.swagger.config.SwaggerProperties;
import com.wangbin.ai.framework.tenant.config.DABINTenantAutoConfiguration;
import com.wangbin.ai.framework.tenant.config.TenantProperties;
import com.wangbin.ai.framework.tracer.config.DABINMetricsAutoConfiguration;
import com.wangbin.ai.framework.tracer.config.DABINTracerAutoConfiguration;
import com.wangbin.ai.framework.tracer.config.TracerProperties;
import com.wangbin.ai.framework.web.config.DABINWebAutoConfiguration;
import com.wangbin.ai.framework.web.config.WebProperties;
import com.wangbin.ai.framework.websocket.config.DABINWebSocketAutoConfiguration;
import com.wangbin.ai.framework.websocket.config.WebSocketProperties;
import com.wangbin.ai.framework.xss.config.DABINXssAutoConfiguration;
import com.wangbin.ai.framework.xss.config.XssProperties;
import com.wangbin.ai.module.infra.framework.codegen.config.CodegenProperties;
import com.wangbin.ai.module.system.framework.sms.config.SmsCodeProperties;
import com.wangbin.ai.framework.apilog.config.DABINApiLogAutoConfiguration;
import com.wangbin.ai.framework.encrypt.config.ApiEncryptProperties;
import com.wangbin.ai.framework.encrypt.config.DABINApiEncryptAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DABINConfigurationPrefixTest {

    private static final Class<?>[] CONFIGURATION_PROPERTIES = {
            DABINCacheProperties.class,
            SecurityProperties.class,
            SwaggerProperties.class,
            TenantProperties.class,
            TracerProperties.class,
            SmsCodeProperties.class,
            WebProperties.class,
            WebSocketProperties.class,
            XssProperties.class,
            ApiEncryptProperties.class,
            CodegenProperties.class
    };

    private static final Class<?>[] CONDITIONAL_CONFIGURATION = {
            DABINApiEncryptAutoConfiguration.class,
            DABINApiLogAutoConfiguration.class,
            DABINMetricsAutoConfiguration.class,
            DABINTenantAutoConfiguration.class,
            DABINTracerAutoConfiguration.class,
            DABINWebAutoConfiguration.class,
            DABINWebSocketAutoConfiguration.class,
            DABINXssAutoConfiguration.class
    };

    @Test
    void configurationPropertiesPrefixesUseSpringBootCanonicalNames() {
        for (Class<?> propertiesClass : CONFIGURATION_PROPERTIES) {
            ConfigurationProperties annotation = propertiesClass.getAnnotation(ConfigurationProperties.class);
            String prefix = annotation.prefix().isBlank() ? annotation.value() : annotation.prefix();
            assertCanonicalPropertyName(prefix);
        }
    }

    @Test
    void conditionalOnPropertyPrefixesUseSpringBootCanonicalNames() {
        for (Class<?> configurationClass : CONDITIONAL_CONFIGURATION) {
            for (AnnotatedElement element : annotatedElements(configurationClass)) {
                ConditionalOnProperty annotation = element.getAnnotation(ConditionalOnProperty.class);
                if (annotation == null) {
                    continue;
                }
                assertConditionalPropertyNames(annotation);
            }
        }
    }

    private void assertConditionalPropertyNames(ConditionalOnProperty annotation) {
        String prefix = annotation.prefix();
        if (!prefix.isBlank()) {
            assertCanonicalPropertyName(prefix);
        }
        for (String name : names(annotation)) {
            String fullName = prefix.isBlank() ? name : prefix + "." + name;
            assertCanonicalPropertyName(fullName);
        }
    }

    private List<String> names(ConditionalOnProperty annotation) {
        List<String> names = new ArrayList<>();
        names.addAll(List.of(annotation.name()));
        names.addAll(List.of(annotation.value()));
        return names;
    }

    private List<AnnotatedElement> annotatedElements(Class<?> configurationClass) {
        List<AnnotatedElement> elements = new ArrayList<>();
        elements.add(configurationClass);
        try {
            elements.addAll(List.of(configurationClass.getDeclaredMethods()));
        } catch (LinkageError ignored) {
            // Optional sender/metrics dependencies may be absent in the server
            // test classpath; class-level condition metadata is still validated.
        }
        for (Class<?> nestedClass : configurationClass.getDeclaredClasses()) {
            elements.addAll(annotatedElements(nestedClass));
        }
        return elements;
    }

    private void assertCanonicalPropertyName(String name) {
        assertThatCode(() -> ConfigurationPropertyName.of(name)).doesNotThrowAnyException();
        assertThat(ConfigurationPropertyName.of(name).toString()).isEqualTo(name);
    }

}
