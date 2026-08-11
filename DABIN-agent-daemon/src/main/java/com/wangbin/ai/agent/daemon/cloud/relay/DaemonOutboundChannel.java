package com.wangbin.ai.agent.daemon.cloud.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.websocket.WsEnvelope;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import org.springframework.stereotype.Component;

import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DaemonOutboundChannel {

    private final ObjectMapper objectMapper;
    private final AgentDaemonProperties properties;
    private final Object monitor = new Object();
    private final Queue<OutboundEnvelope> queue = new ArrayDeque<>();
    private final Queue<ReliableRecord> reliablePending = new ArrayDeque<>();
    private final Set<Long> queuedReliableIds = new HashSet<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private long reliableSequence;

    public DaemonOutboundChannel(ObjectMapper objectMapper, AgentDaemonProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean enqueue(WebSocket socket, WsEnvelope<?> envelope, Runnable failureHandler) {
        return enqueue(socket, envelope, OutboundReliability.TRANSIENT, failureHandler);
    }

    public boolean enqueue(WebSocket socket, WsEnvelope<?> envelope, OutboundReliability reliability,
                           Runnable failureHandler) {
        if (reliability == OutboundReliability.RELIABLE) {
            return enqueueReliable(socket, envelope, failureHandler);
        }
        if (socket == null) {
            return false;
        }
        synchronized (monitor) {
            if (!offerLocked(new OutboundEnvelope(socket, envelope, reliability, null, failureHandler))) {
                return false;
            }
        }
        drain();
        return true;
    }

    public boolean replayReliable(WebSocket socket, Runnable failureHandler) {
        if (socket == null) {
            return false;
        }
        boolean queuedAll = true;
        synchronized (monitor) {
            for (ReliableRecord record : reliablePending) {
                if (queuedReliableIds.contains(record.id())) {
                    continue;
                }
                if (!offerLocked(new OutboundEnvelope(socket, record.envelope(),
                        OutboundReliability.RELIABLE, record.id(), failureHandler))) {
                    queuedAll = false;
                    break;
                }
            }
        }
        drain();
        if (!queuedAll && failureHandler != null) {
            failureHandler.run();
        }
        return queuedAll;
    }

    public void removeQueuedForSocket(WebSocket socket) {
        if (socket == null) {
            return;
        }
        synchronized (monitor) {
            queue.removeIf(envelope -> {
                boolean remove = envelope.socket() == socket;
                if (remove && envelope.reliableId() != null) {
                    queuedReliableIds.remove(envelope.reliableId());
                }
                return remove;
            });
        }
    }

    public void clear() {
        synchronized (monitor) {
            queue.clear();
            reliablePending.clear();
            queuedReliableIds.clear();
        }
    }

    int reliablePendingSize() {
        synchronized (monitor) {
            return reliablePending.size();
        }
    }

    private boolean enqueueReliable(WebSocket socket, WsEnvelope<?> envelope, Runnable failureHandler) {
        boolean queued = true;
        synchronized (monitor) {
            if (reliablePending.size() >= properties.getReliableOutboundCapacity()) {
                return false;
            }
            ReliableRecord record = new ReliableRecord(++reliableSequence, envelope);
            reliablePending.add(record);
            if (socket != null) {
                queued = offerLocked(new OutboundEnvelope(socket, envelope, OutboundReliability.RELIABLE,
                        record.id(), failureHandler));
            }
        }
        drain();
        if (!queued && failureHandler != null) {
            failureHandler.run();
        }
        return true;
    }

    private boolean offerLocked(OutboundEnvelope envelope) {
        if (queue.size() >= properties.getOutboundQueueCapacity()) {
            return false;
        }
        queue.add(envelope);
        if (envelope.reliableId() != null) {
            queuedReliableIds.add(envelope.reliableId());
        }
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
            if (outbound.reliableId() != null) {
                queuedReliableIds.remove(outbound.reliableId());
            }
        }
        try {
            String json = objectMapper.writeValueAsString(outbound.envelope());
            outbound.socket().sendText(json, true).whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    removeReliablePending(outbound.reliableId());
                } else if (outbound.failureHandler() != null) {
                    outbound.failureHandler().run();
                }
                sendNext();
            });
        } catch (Exception ex) {
            if (outbound.failureHandler() != null) {
                outbound.failureHandler().run();
            }
            sendNext();
        }
    }

    private void removeReliablePending(Long reliableId) {
        if (reliableId == null) {
            return;
        }
        synchronized (monitor) {
            reliablePending.removeIf(record -> record.id() == reliableId);
        }
    }

    private record ReliableRecord(long id, WsEnvelope<?> envelope) {
    }

    private record OutboundEnvelope(WebSocket socket, WsEnvelope<?> envelope, OutboundReliability reliability,
                                    Long reliableId, Runnable failureHandler) {
    }
}
