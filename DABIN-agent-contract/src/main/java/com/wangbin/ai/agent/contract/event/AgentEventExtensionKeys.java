package com.wangbin.ai.agent.contract.event;

/**
 * Stable extension keys shared by daemon, relay and control plane. These keys
 * are platform protocol fields and must not be replaced by native Agent ids.
 */
public final class AgentEventExtensionKeys {

    public static final String PLATFORM_COMMAND_ID = "platformCommandId";
    public static final String NATIVE_METHOD = "nativeMethod";
    public static final String NATIVE_ITEM_ID = "nativeItemId";
    public static final String NATIVE_ITEM_TYPE = "nativeItemType";
    public static final String NATIVE_PHASE = "nativePhase";
    public static final String NATIVE_STATUS = "nativeStatus";

    private AgentEventExtensionKeys() {
    }
}
