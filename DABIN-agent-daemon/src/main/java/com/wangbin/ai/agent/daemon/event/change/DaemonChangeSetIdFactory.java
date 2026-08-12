package com.wangbin.ai.agent.daemon.event.change;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class DaemonChangeSetIdFactory {

    private static final String CHANGE_SET_PREFIX = "chg_";
    private static final int RANDOM_BYTE_LENGTH = 18;

    private final SecureRandom secureRandom = new SecureRandom();

    public String changeSetId() {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return CHANGE_SET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
