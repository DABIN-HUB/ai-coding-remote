package com.wangbin.ai.agent.daemon.cloud.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.websocket.WsEnvelope;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import org.springframework.stereotype.Component;

import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private WebSocket activeBusinessSocket;
    private Runnable activeBusinessFailureHandler;

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

    /**
     * Activates the business socket only after all existing reliable records are
     * bound to the new socket queue. Concurrent reliable sends can only enter the
     * monitor after this point, so they are appended after the replay backlog.
     */
    public boolean activateConnectionAndReplay(WebSocket socket, Runnable failureHandler) {
        return activateConnectionAndReplay(socket, failureHandler, null);
    }

    boolean activateConnectionAndReplay(WebSocket socket, Runnable failureHandler, Runnable beforeReplayHook) {
        if (socket == null) {
            return false;
        }
        boolean queuedAll;
        List<Long> activationQueuedIds = new ArrayList<>();
        synchronized (monitor) {
            if (beforeReplayHook != null) {
                beforeReplayHook.run();
            }
            queuedAll = queueReliablePendingLocked(socket, failureHandler, activationQueuedIds);
            if (queuedAll) {
                activeBusinessSocket = socket;
                activeBusinessFailureHandler = failureHandler;
            } else {
                rollbackActivationLocked(socket, activationQueuedIds);
            }
        }
        if (queuedAll) {
            drain();
        } else if (failureHandler != null) {
            failureHandler.run();
        }
        return queuedAll;
    }

    public boolean replayReliable(WebSocket socket, Runnable failureHandler) {
        return activateConnectionAndReplay(socket, failureHandler);
    }

    public void removeQueuedForSocket(WebSocket socket) {
        if (socket == null) {
            return;
        }
        synchronized (monitor) {
            if (activeBusinessSocket == socket) {
                activeBusinessSocket = null;
                activeBusinessFailureHandler = null;
            }
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
            activeBusinessSocket = null;
            activeBusinessFailureHandler = null;
        }
    }

    int reliablePendingSize() {
        synchronized (monitor) {
            return reliablePending.size();
        }
    }

    private boolean enqueueReliable(WebSocket socket, WsEnvelope<?> envelope, Runnable failureHandler) {
        boolean queued = true;
        Runnable effectiveFailureHandler;
        synchronized (monitor) {
            if (reliablePending.size() >= properties.getReliableOutboundCapacity()) {
                return false;
            }
            ReliableRecord record = new ReliableRecord(++reliableSequence, envelope);
            reliablePending.add(record);
            WebSocket targetSocket = activeBusinessSocket != null ? activeBusinessSocket : socket;
            effectiveFailureHandler = activeBusinessSocket != null ? activeBusinessFailureHandler : failureHandler;
            if (targetSocket != null) {
                queued = offerLocked(new OutboundEnvelope(targetSocket, envelope, OutboundReliability.RELIABLE,
                        record.id(), effectiveFailureHandler));
            }
        }
        drain();
        if (!queued && effectiveFailureHandler != null) {
            effectiveFailureHandler.run();
        }
        return true;
    }

    private boolean queueReliablePendingLocked(WebSocket socket, Runnable failureHandler, List<Long> queuedIds) {
        for (ReliableRecord record : reliablePending) {
            if (queuedReliableIds.contains(record.id())) {
                continue;
            }
            if (!offerLocked(new OutboundEnvelope(socket, record.envelope(),
                    OutboundReliability.RELIABLE, record.id(), failureHandler))) {
                return false;
            }
            queuedIds.add(record.id());
        }
        return true;
    }

    private void rollbackActivationLocked(WebSocket socket, List<Long> queuedIds) {
        queue.removeIf(envelope -> envelope.socket() == socket
                && envelope.reliableId() != null
                && queuedIds.contains(envelope.reliableId()));
        queuedReliableIds.removeAll(queuedIds);
        if (activeBusinessSocket == socket) {
            activeBusinessSocket = null;
            activeBusinessFailureHandler = null;
        }
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
