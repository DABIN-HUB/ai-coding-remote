package com.wangbin.ai.agent.daemon.command;

import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class InMemoryCommandDedupCache implements CommandDedupCache {

    private final AgentDaemonProperties properties;
    private final Map<String, Instant> commands = new LinkedHashMap<>();

    public InMemoryCommandDedupCache(AgentDaemonProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized CommandDedupResult reserve(String commandId) {
        evictExpired();
        if (commands.containsKey(commandId)) {
            return CommandDedupResult.DUPLICATE;
        }
        commands.put(commandId, Instant.now());
        evictOverflow();
        return CommandDedupResult.RESERVED;
    }

    @Override
    public synchronized void markCompleted(String commandId) {
        commands.put(commandId, Instant.now());
        evictOverflow();
    }

    @Override
    public synchronized void release(String commandId) {
        commands.remove(commandId);
    }

    private void evictExpired() {
        Instant deadline = Instant.now().minus(properties.getCommandDedupTtl());
        Iterator<Map.Entry<String, Instant>> iterator = commands.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isBefore(deadline)) {
                iterator.remove();
            }
        }
    }

    private void evictOverflow() {
        Iterator<String> iterator = commands.keySet().iterator();
        while (commands.size() > properties.getCommandDedupCapacity() && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
