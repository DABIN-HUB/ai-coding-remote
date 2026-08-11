package com.wangbin.ai.module.agent.service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wangbin.ai.agent.contract.command.CommandAck;
import com.wangbin.ai.agent.contract.command.CommandAckStatus;
import com.wangbin.ai.agent.contract.coordination.AgentCoordinationKeys;
import com.wangbin.ai.agent.contract.coordination.AgentEventIngressPayload;
import com.wangbin.ai.agent.contract.coordination.CommandAckIngressPayload;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentSessionStatus;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.event.AgentEvent;
import com.wangbin.ai.agent.contract.event.SessionPayload;
import com.wangbin.ai.framework.tenant.core.context.TenantContextHolder;
import com.wangbin.ai.module.agent.framework.config.AgentControlPlaneProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisAgentEventIngressConsumerTest {

    private static final Long TEST_TENANT_ID = 1L;
    private static final Long OLD_TENANT_ID = 99L;
    private static final Long TEST_USER_ID = 11L;
    private static final String TEST_DEVICE_ID = "dev-1";
    private static final String TEST_PROJECT_ID = "prj-1";
    private static final String TEST_SESSION_ID = "ses-1";
    private static final String TEST_RELAY_NODE_ID = "relay-1";
    private static final String TEST_CONNECTION_ID = "conn-1";
    private static final String TEST_COMMAND_ID = "cmd-1";

    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final AgentEventIngressService ingressService = mock(AgentEventIngressService.class);
    private final AgentControlPlaneProperties properties = new AgentControlPlaneProperties();
    private final RedisAgentEventIngressConsumer consumer =
            new RedisAgentEventIngressConsumer(stringRedisTemplate, objectMapper, ingressService, properties);

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void ensureConsumerGroupCreatesFromZeroWithMkstream() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisStreamCommands streamCommands = mock(RedisStreamCommands.class);
        when(connection.streamCommands()).thenReturn(streamCommands);
        doAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            callback.doInRedis(connection);
            return null;
        }).when(stringRedisTemplate).execute(any(RedisCallback.class));

        consumer.ensureConsumerGroup();

        ArgumentCaptor<ReadOffset> offsetCaptor = ArgumentCaptor.forClass(ReadOffset.class);
        verify(streamCommands).xGroupCreate(argThat(bytes -> Arrays.equals(bytes,
                        raw(AgentCoordinationKeys.eventIngressStream()))),
                eq(properties.getEventIngressConsumerGroup()), offsetCaptor.capture(), eq(true));
        assertThat(offsetCaptor.getValue().toString()).contains("0-0");
    }

    @Test
    void handleAgentEventRecordSetsTenantContextAndAcksAfterServiceSuccess() throws Exception {
        StreamOperations streamOperations = mock(StreamOperations.class);
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        TenantContextHolder.setTenantId(OLD_TENANT_ID);
        doAnswer(invocation -> {
            assertThat(TenantContextHolder.getTenantId()).isEqualTo(TEST_TENANT_ID);
            return null;
        }).when(ingressService).handleAgentEvent(any(AgentEventIngressPayload.class));
        MapRecord<String, String, String> record = record(AgentCoordinationKeys.EVENT_INGRESS_TYPE_AGENT_EVENT,
                objectMapper.writeValueAsString(eventPayload()));

        consumer.handleRecord(record);

        assertThat(TenantContextHolder.getTenantId()).isEqualTo(OLD_TENANT_ID);
        verify(ingressService).handleAgentEvent(any(AgentEventIngressPayload.class));
        verify(streamOperations).acknowledge(AgentCoordinationKeys.eventIngressStream(),
                properties.getEventIngressConsumerGroup(), record.getId());
    }

    @Test
    void handleCommandAckRecordSetsTenantContextAndDoesNotAckWhenServiceFails() throws Exception {
        StreamOperations streamOperations = mock(StreamOperations.class);
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        TenantContextHolder.setTenantId(OLD_TENANT_ID);
        doThrow(new IllegalStateException("db failed")).when(ingressService)
                .handleCommandAck(any(CommandAckIngressPayload.class));
        MapRecord<String, String, String> record = record(AgentCoordinationKeys.EVENT_INGRESS_TYPE_COMMAND_ACK,
                objectMapper.writeValueAsString(ackPayload()));

        consumer.handleRecord(record);

        assertThat(TenantContextHolder.getTenantId()).isEqualTo(OLD_TENANT_ID);
        verify(ingressService).handleCommandAck(any(CommandAckIngressPayload.class));
        verify(streamOperations, never()).acknowledge(AgentCoordinationKeys.eventIngressStream(),
                properties.getEventIngressConsumerGroup(), record.getId());
    }

    private AgentEventIngressPayload eventPayload() {
        AgentEvent event = AgentEvent.of("trace-1", TEST_TENANT_ID, TEST_USER_ID, TEST_DEVICE_ID, TEST_PROJECT_ID,
                TEST_SESSION_ID, 1L, AgentType.CODEX, AgentEventType.SESSION_IDLE,
                new SessionPayload("native-1", AgentSessionStatus.IDLE, null, Map.of()));
        return new AgentEventIngressPayload(TEST_RELAY_NODE_ID, TEST_CONNECTION_ID, TEST_TENANT_ID,
                TEST_USER_ID, TEST_DEVICE_ID, event, Instant.now());
    }

    private CommandAckIngressPayload ackPayload() {
        CommandAck ack = new CommandAck(TEST_COMMAND_ID, TEST_SESSION_ID, TEST_DEVICE_ID,
                CommandAckStatus.ACCEPTED, "ACCEPTED", "accepted", Instant.now(), Map.of());
        return new CommandAckIngressPayload(TEST_RELAY_NODE_ID, TEST_CONNECTION_ID, TEST_TENANT_ID,
                TEST_USER_ID, TEST_DEVICE_ID, ack, Instant.now());
    }

    private MapRecord<String, String, String> record(String type, String payload) {
        return StreamRecords.string(Map.of(
                        AgentCoordinationKeys.EVENT_INGRESS_FIELD_TYPE, type,
                        AgentCoordinationKeys.EVENT_INGRESS_FIELD_PAYLOAD, payload))
                .withStreamKey(AgentCoordinationKeys.eventIngressStream())
                .withId(RecordId.of("1-0"));
    }

    private byte[] raw(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
