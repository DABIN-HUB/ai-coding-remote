package com.wangbin.ai.agent.daemon.cloud.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.daemon.cloud.controlplane.ControlPlaneClient;
import com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceRequest;
import com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceResponse;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RelayTicketResponse;
import com.wangbin.ai.agent.daemon.cloud.relay.DaemonOutboundChannel;
import com.wangbin.ai.agent.daemon.cloud.relay.RelayWebSocketClient;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.state.DaemonStateStore;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonCloudRunnerTest {

    @Test
    void runModeShouldKeepProcessAliveUntilLifecycleStops() throws Exception {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        DaemonRunLifecycle runLifecycle = new DaemonRunLifecycle();
        DeviceCredentialState credential = credential();
        FakeStateStore stateStore = new FakeStateStore(credential);
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        RecordingRelayWebSocketClient relayWebSocketClient = new RecordingRelayWebSocketClient();

        DaemonCloudRunner runner = new DaemonCloudRunner(properties, stateStore, new FakeControlPlaneClient(),
                relayWebSocketClient, bootstrap, runLifecycle);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> runner.run(new DefaultApplicationArguments("--mode=run")));

            assertThat(bootstrap.awaitCalled()).isTrue();
            assertThat(relayWebSocketClient.awaitCalled()).isTrue();
            assertThat(future.isDone()).isFalse();

            runLifecycle.stop();
            future.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            relayWebSocketClient.shutdown();
        }
    }

    private DeviceCredentialState credential() {
        DeviceCredentialState state = new DeviceCredentialState();
        state.setTenantId(1L);
        state.setDeviceId("dev_smoke");
        state.setCredentialId("cred_smoke");
        state.setCredentialSecret("secret");
        state.setPairedAt(Instant.now());
        state.setControlPlaneUrl("http://127.0.0.1:48080/admin-api");
        state.setRelayUrl("ws://127.0.0.1:48180/agent/ws");
        return state;
    }

    private static class FakeStateStore extends DaemonStateStore {

        private final DeviceCredentialState credential;

        FakeStateStore(DeviceCredentialState credential) {
            super(new ObjectMapper());
            this.credential = credential;
        }

        @Override
        public synchronized Optional<DeviceCredentialState> loadCredential() {
            return Optional.of(credential);
        }
    }

    private static class RecordingBootstrap extends DaemonProjectRuntimeBootstrap {

        private final CountDownLatch called = new CountDownLatch(1);

        RecordingBootstrap() {
            super(null, null, null, null, null, List.of());
        }

        @Override
        public void bootstrap(DeviceCredentialState credential) {
            called.countDown();
        }

        boolean awaitCalled() throws InterruptedException {
            return called.await(1, TimeUnit.SECONDS);
        }
    }

    private static class RecordingRelayWebSocketClient extends RelayWebSocketClient {

        private final CountDownLatch called = new CountDownLatch(1);
        private final ScheduledExecutorService scheduler;

        RecordingRelayWebSocketClient() {
            this(Executors.newSingleThreadScheduledExecutor());
        }

        private RecordingRelayWebSocketClient(ScheduledExecutorService scheduler) {
            super(new ObjectMapper(), new AgentDaemonProperties(), new FakeControlPlaneClient(), scheduler,
                    (command, credential, outboundSender) -> {
                    }, new DaemonOutboundChannel(new ObjectMapper(), new AgentDaemonProperties()));
            this.scheduler = scheduler;
        }

        @Override
        public void start(DeviceCredentialState credential) {
            called.countDown();
        }

        boolean awaitCalled() throws InterruptedException {
            return called.await(1, TimeUnit.SECONDS);
        }

        void shutdown() {
            scheduler.shutdownNow();
        }
    }

    private static class FakeControlPlaneClient implements ControlPlaneClient {

        @Override
        public PairDeviceResponse pair(String controlPlaneUrl, PairDeviceRequest request) {
            return null;
        }

        @Override
        public RelayTicketResponse createDeviceRelayTicket(DeviceCredentialState credential) {
            return null;
        }
    }
}
