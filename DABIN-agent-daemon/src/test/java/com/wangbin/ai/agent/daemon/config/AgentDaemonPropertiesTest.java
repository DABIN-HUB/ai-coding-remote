package com.wangbin.ai.agent.daemon.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDaemonPropertiesTest {

    private static final int OUTBOUND_CAPACITY = 8;
    private static final int SMALLER_RELIABLE_CAPACITY = 4;
    private static final int LARGER_RELIABLE_CAPACITY = 9;
    private static final String CAPACITY_ERROR_MESSAGE =
            "reliableOutboundCapacity must be less than or equal to outboundQueueCapacity";

    @Test
    void reliableCapacityMayBeSmallerThanOutboundCapacity() {
        AgentDaemonProperties properties = properties(OUTBOUND_CAPACITY, SMALLER_RELIABLE_CAPACITY);

        assertThat(validate(properties)).isEmpty();
    }

    @Test
    void reliableCapacityMayEqualOutboundCapacity() {
        AgentDaemonProperties properties = properties(OUTBOUND_CAPACITY, OUTBOUND_CAPACITY);

        assertThat(validate(properties)).isEmpty();
    }

    @Test
    void reliableCapacityMustNotExceedOutboundCapacity() {
        AgentDaemonProperties properties = properties(OUTBOUND_CAPACITY, LARGER_RELIABLE_CAPACITY);

        assertThat(validate(properties))
                .anySatisfy(violation -> assertThat(violation.getMessage()).isEqualTo(CAPACITY_ERROR_MESSAGE));
    }

    private AgentDaemonProperties properties(int outboundCapacity, int reliableCapacity) {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        properties.setOutboundQueueCapacity(outboundCapacity);
        properties.setReliableOutboundCapacity(reliableCapacity);
        return properties;
    }

    private Set<ConstraintViolation<AgentDaemonProperties>> validate(AgentDaemonProperties properties) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(properties);
        }
    }
}
