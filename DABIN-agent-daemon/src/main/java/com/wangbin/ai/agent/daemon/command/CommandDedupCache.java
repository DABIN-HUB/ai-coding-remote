package com.wangbin.ai.agent.daemon.command;

public interface CommandDedupCache {

    CommandDedupResult reserve(String commandId);

    void markCompleted(String commandId);

    void release(String commandId);
}
