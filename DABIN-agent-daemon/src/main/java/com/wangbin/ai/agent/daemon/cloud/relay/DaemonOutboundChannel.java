package com.wangbin.ai.agent.daemon.cloud.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.websocket.WsEnvelope;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.exception.AgentProtocolException;
import org.springframework.stereotype.Component;

import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DaemonOutboundChannel {

    private final ObjectMapper objectMapper;
    private final AgentDaemonProperties properties;
    private final Object monitor = new Object();
    private final Queue<OutboundEnvelope> queue = new ArrayDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public DaemonOutboundChannel(ObjectMapper objectMapper, AgentDaemonProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean enqueue(WebSocket socket, WsEnvelope<?> envelope, Runnable failureHandler) {
        synchronized (monitor) {
            if (queue.size() >= properties.getOutboundQueueCapacity()) {
                return false;
            }
            queue.add(new OutboundEnvelope(socket, envelope, failureHandler));
        }
        drain();
        return true;
    }

    private void drain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        sendNext();
    }

    private void sendNext() {
        OutboundEnvelope outbound;
        synchronized (monitor) {
            outbound = queue.poll();
            if (outbound == null) {
                draining.set(false);
                if (!queue.isEmpty()) {
                    drain();
                }
                return;
            }
        }
        try {
            String json = objectMapper.writeValueAsString(outbound.envelope());
            outbound.socket().sendText(json, true).whenComplete((ignored, throwable) -> {
                if (throwable != null && outbound.failureHandler() != null) {
                    outbound.failureHandler().run();
                }
                sendNext();
            });
        } catch (Exception ex) {
            if (outbound.failureHandler() != null) {
                outbound.failureHandler().run();
            }
            throw new AgentProtocolException("failed to serialize daemon outbound WebSocket message", ex);
        }
    }

    private record OutboundEnvelope(WebSocket socket, WsEnvelope<?> envelope, Runnable failureHandler) {
    }
}
