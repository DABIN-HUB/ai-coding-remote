package com.wangbin.ai.agent.daemon.smoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.enums.AgentEventType;
import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.contract.event.AgentErrorPayload;
import com.wangbin.ai.agent.contract.session.AgentSession;
import com.wangbin.ai.agent.contract.session.PromptCommand;
import com.wangbin.ai.agent.contract.session.SessionStartRequest;
import com.wangbin.ai.agent.daemon.adapter.codex.CodexAppServerAdapter;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.runtime.RuntimeDiscovery;
import com.wangbin.ai.agent.daemon.runtime.RuntimeDiscoveryResult;
import com.wangbin.ai.agent.daemon.runtime.RuntimeInstallStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class CodexSmokeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CodexSmokeRunner.class);

    private final RuntimeDiscovery runtimeDiscovery;
    private final CodexAppServerAdapter codexAdapter;
    private final AgentDaemonProperties daemonProperties;
    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext applicationContext;

    public CodexSmokeRunner(RuntimeDiscovery runtimeDiscovery,
                            CodexAppServerAdapter codexAdapter,
                            AgentDaemonProperties daemonProperties,
                            ObjectMapper objectMapper,
                            ConfigurableApplicationContext applicationContext) {
        this.runtimeDiscovery = runtimeDiscovery;
        this.codexAdapter = codexAdapter;
        this.daemonProperties = daemonProperties;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("workspace") || !args.containsOption("prompt")) {
            log.info("Codex smoke runner is idle. Provide --workspace=<path> --prompt=<text> to execute a real smoke test.");
            return;
        }

        String workspace = requiredOption(args, "workspace");
        String prompt = requiredOption(args, "prompt");
        RuntimeDiscoveryResult discovery = runtimeDiscovery.discover(AgentType.CODEX);
        log.info("Codex runtime discovery: status={}, executable={}, version={}, error={}",
                discovery.status(), discovery.executable(), discovery.version(), discovery.diagnostic());
        if (discovery.status() != RuntimeInstallStatus.INSTALLED) {
            applicationContext.close();
            throw new IllegalStateException("Codex is not installed or not executable: " + discovery.diagnostic());
        }

        AgentSession session = codexAdapter.startSession(new SessionStartRequest(
                1L,
                1L,
                "local-dev-device",
                "local-dev-project",
                workspace,
                AgentType.CODEX,
                Map.of("mode", "smoke")
        ));
        CountDownLatch terminalEvent = new CountDownLatch(1);
        AtomicReference<String> errorMessage = new AtomicReference<>();
        AtomicBoolean successfulTerminal = new AtomicBoolean(false);
        Disposable subscription = codexAdapter.events(session.platformSessionId()).subscribe(event -> {
            try {
                System.out.println(objectMapper.writeValueAsString(event));
            } catch (Exception ex) {
                log.warn("failed to serialize AgentEvent: sessionId={}, eventType={}",
                        event.sessionId(), event.type(), ex);
            }
            if (event.type() == AgentEventType.ERROR) {
                errorMessage.set(String.valueOf(event.payload()));
                if (!(event.payload() instanceof AgentErrorPayload payload) || !payload.retryable()) {
                    terminalEvent.countDown();
                }
            }
            if (isSuccessfulTerminal(event.type())) {
                successfulTerminal.set(true);
                terminalEvent.countDown();
            }
        });

        try {
            codexAdapter.sendPrompt(session.platformSessionId(), new PromptCommand(null, prompt, Map.of("mode", "smoke")));
            boolean completed = terminalEvent.await(daemonProperties.getSmoke().getTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new IllegalStateException("Codex smoke test timed out after "
                        + daemonProperties.getSmoke().getTimeout()
                        + (errorMessage.get() == null ? "" : ", last AgentEvent ERROR: " + errorMessage.get()));
            }
            if (!successfulTerminal.get() && errorMessage.get() != null) {
                throw new IllegalStateException("Codex smoke test failed with AgentEvent ERROR: "
                        + errorMessage.get());
            }
        } finally {
            subscription.dispose();
            codexAdapter.closeSession(session.platformSessionId());
            applicationContext.close();
        }
    }

    private boolean isSuccessfulTerminal(AgentEventType type) {
        return type == AgentEventType.SESSION_IDLE
                || type == AgentEventType.SESSION_COMPLETED;
    }

    private String requiredOption(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("missing required option --" + name);
        }
        return values.getFirst();
    }

}
