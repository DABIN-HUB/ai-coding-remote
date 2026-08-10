package com.wangbin.ai.agent.daemon.process;

public interface ProcessCommandResolver {

    ResolvedCommand resolve(String executable);

}
