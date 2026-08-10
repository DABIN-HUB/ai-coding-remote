package com.wangbin.ai.agent.daemon.process;

import java.util.function.Consumer;

public interface ProcessSupervisor {

    ManagedProcess start(ProcessSpec spec, Consumer<String> stderrConsumer);

}
