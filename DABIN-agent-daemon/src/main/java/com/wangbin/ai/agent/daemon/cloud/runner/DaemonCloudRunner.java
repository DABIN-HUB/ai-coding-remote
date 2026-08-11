package com.wangbin.ai.agent.daemon.cloud.runner;

import com.wangbin.ai.agent.daemon.cloud.controlplane.ControlPlaneClient;
import com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceRequest;
import com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceResponse;
import com.wangbin.ai.agent.daemon.cloud.relay.RelayWebSocketClient;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.state.DaemonStateStore;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;

/**
 * Explicit cloud runner. It only enters PAIR/RUN when requested by command-line
 * options, so ordinary daemon startup and Codex smoke mode remain isolated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DaemonCloudRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DaemonCloudRunner.class);
    private static final String OPTION_MODE = "mode";
    private static final String MODE_PAIR = "pair";
    private static final String MODE_RUN = "run";

    private final AgentDaemonProperties properties;
    private final DaemonStateStore stateStore;
    private final ControlPlaneClient controlPlaneClient;
    private final RelayWebSocketClient relayWebSocketClient;
    private final DaemonProjectRuntimeBootstrap projectRuntimeBootstrap;
    private final DaemonRunLifecycle runLifecycle;

    public DaemonCloudRunner(AgentDaemonProperties properties,
                             DaemonStateStore stateStore,
                             ControlPlaneClient controlPlaneClient,
                             RelayWebSocketClient relayWebSocketClient,
                             DaemonProjectRuntimeBootstrap projectRuntimeBootstrap,
                             DaemonRunLifecycle runLifecycle) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.controlPlaneClient = controlPlaneClient;
        this.relayWebSocketClient = relayWebSocketClient;
        this.projectRuntimeBootstrap = projectRuntimeBootstrap;
        this.runLifecycle = runLifecycle;
    }

    @Override
    public void run(ApplicationArguments args) {
        String mode = option(args, OPTION_MODE, "");
        if (MODE_PAIR.equalsIgnoreCase(mode)) {
            pair(args);
            return;
        }
        if (MODE_RUN.equalsIgnoreCase(mode)) {
            runCloudTransport();
        }
    }

    private void pair(ApplicationArguments args) {
        String pairingCode = requiredOption(args, "pairingCode");
        String controlPlaneUrl = option(args, "controlPlaneUrl", properties.getCloud().getControlPlaneUrl());
        String relayUrl = option(args, "relayUrl", properties.getCloud().getRelayUrl());
        String installationId = stateStore.getOrCreateInstallationId();
        PairDeviceRequest request = new PairDeviceRequest(pairingCode, installationId,
                option(args, "deviceName", defaultDeviceName()),
                defaultHostname(), System.getProperty("os.name", ""),
                System.getProperty("os.version", ""), System.getProperty("os.arch", ""),
                option(args, "daemonVersion", "0.1.0"));
        PairDeviceResponse response = controlPlaneClient.pair(controlPlaneUrl, request);
        DeviceCredentialState credential = stateStore.newCredential(response.tenantId(), response.deviceId(),
                response.credentialId(), response.credentialSecret(), controlPlaneUrl, relayUrl);
        stateStore.saveCredential(credential);
        log.info("daemon paired successfully: tenantId={}, deviceId={}, credentialId={}",
                response.tenantId(), response.deviceId(), response.credentialId());
    }

    private void runCloudTransport() {
        stateStore.loadCredential().ifPresentOrElse(credential -> {
                    projectRuntimeBootstrap.bootstrap(credential);
                    relayWebSocketClient.start(credential);
                    runLifecycle.awaitStop();
                },
                () -> log.warn("daemon has no device credential; run --mode=pair --pairingCode=<code> first"));
    }

    private String requiredOption(ApplicationArguments args, String name) {
        String value = option(args, name, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required option --" + name);
        }
        return value;
    }

    private String option(ApplicationArguments args, String name, String defaultValue) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? defaultValue : values.getFirst();
    }

    private String defaultDeviceName() {
        String user = System.getProperty("user.name", "user");
        return user + "@" + defaultHostname();
    }

    private String defaultHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "";
        }
    }
}
