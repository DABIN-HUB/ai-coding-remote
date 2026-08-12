package com.wangbin.ai.module.agent.framework.id;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AgentIdFactory {

    private static final String PROJECT_PREFIX = "prj_";
    private static final String RUNTIME_PREFIX = "rt_";
    private static final String SESSION_PREFIX = "ses_";
    private static final String COMMAND_PREFIX = "cmd_";
    private static final String MESSAGE_PREFIX = "msg_";
    private static final String PERMISSION_PREFIX = "perm_";
    private static final int RANDOM_BYTE_LENGTH = 18;

    private final SecureRandom secureRandom = new SecureRandom();

    public String projectId() {
        return PROJECT_PREFIX + randomToken();
    }

    public String runtimeId() {
        return RUNTIME_PREFIX + randomToken();
    }

    public String sessionId() {
        return SESSION_PREFIX + randomToken();
    }

    public String commandId() {
        return COMMAND_PREFIX + randomToken();
    }

    public String messageId() {
        return MESSAGE_PREFIX + randomToken();
    }

    public String permissionId() {
        return PERMISSION_PREFIX + randomToken();
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
