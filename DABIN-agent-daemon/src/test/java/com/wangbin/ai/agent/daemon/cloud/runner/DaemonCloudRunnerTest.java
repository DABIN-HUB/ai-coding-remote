package com.wangbin.ai.agent.daemon.cloud.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.daemon.cloud.controlplane.ControlPlaneClient;
import com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceRequest;
import com.wangbin.ai.agent.daemon.cloud.controlplane.PairDeviceResponse;
import com.wangbin.ai.agent.daemon.cloud.controlplane.RelayTicketResponse;
import com.wangbin.ai.agent.daemon.cloud.relay.DaemonOutboundChannel;
import com.wangbin.ai.agent.daemon.cloud.relay.RelayWebSocketClient;
import com.wangbin.ai.agent.daemon.config.AgentDaemonProperties;
import com.wangbin.ai.agent.daemon.project.LocalProjectSetupService;
import com.wangbin.ai.agent.daemon.state.DaemonStateStore;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonCloudRunnerTest {

    @Test
    void runModeShouldStartCloudAndReturnAfterRelayLoopStarts() throws Exception {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        DaemonRunLifecycle runLifecycle = new DaemonRunLifecycle();
        DeviceCredentialState credential = credential();
        FakeStateStore stateStore = new FakeStateStore(credential);
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        RecordingProjectSetupService projectSetupService = new RecordingProjectSetupService();
        RecordingRelayWebSocketClient relayWebSocketClient = new RecordingRelayWebSocketClient();

        DaemonCloudRunner runner = new DaemonCloudRunner(properties, stateStore, new FakeControlPlaneClient(),
                relayWebSocketClient, bootstrap, projectSetupService, runLifecycle);

        runner.run(new DefaultApplicationArguments("--mode=run"));

        assertThat(bootstrap.awaitCalled()).isTrue();
        assertThat(projectSetupService.awaitCalled()).isTrue();
        assertThat(relayWebSocketClient.awaitCalled()).isTrue();
        assertThat(runLifecycle.isRunning()).isTrue();
        runLifecycle.stop();
        relayWebSocketClient.shutdown();
    }

    @Test
    void pairingCodeShouldPairThenStartCloudAndReturnAfterRelayLoopStarts() throws Exception {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        DaemonRunLifecycle runLifecycle = new DaemonRunLifecycle();
        FakeStateStore stateStore = new FakeStateStore(null);
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        RecordingProjectSetupService projectSetupService = new RecordingProjectSetupService();
        RecordingRelayWebSocketClient relayWebSocketClient = new RecordingRelayWebSocketClient();
        FakeControlPlaneClient controlPlaneClient = new FakeControlPlaneClient();

        DaemonCloudRunner runner = new DaemonCloudRunner(properties, stateStore, controlPlaneClient,
                relayWebSocketClient, bootstrap, projectSetupService, runLifecycle);

        runner.run(new DefaultApplicationArguments("--pairingCode=ABCD-EFGH"));

        assertThat(controlPlaneClient.awaitPaired()).isTrue();
        assertThat(projectSetupService.awaitCalled()).isTrue();
        assertThat(projectSetupService.promptIfEmpty).isTrue();
        assertThat(bootstrap.awaitCalled()).isTrue();
        assertThat(relayWebSocketClient.awaitCalled()).isTrue();
        assertThat(runLifecycle.isRunning()).isTrue();
        runLifecycle.stop();
        relayWebSocketClient.shutdown();
    }

    @Test
    void addProjectModeShouldOnlyUpdateLocalProjectStoreAndExit() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        DaemonRunLifecycle runLifecycle = new DaemonRunLifecycle();
        RecordingProjectSetupService projectSetupService = new RecordingProjectSetupService();
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        RecordingRelayWebSocketClient relayWebSocketClient = new RecordingRelayWebSocketClient();
        DaemonCloudRunner runner = new DaemonCloudRunner(properties, new FakeStateStore(credential()),
                new FakeControlPlaneClient(), relayWebSocketClient, bootstrap, projectSetupService, runLifecycle);

        runner.run(new DefaultApplicationArguments("--mode=add-project", "--projectPath=target/project-a"));

        assertThat(projectSetupService.called).isTrue();
        assertThat(bootstrap.isCalled()).isFalse();
        assertThat(relayWebSocketClient.isCalled()).isFalse();
        relayWebSocketClient.shutdown();
    }

    @Test
    void projectAddSubCommandShouldBeSupported() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        RecordingProjectSetupService projectSetupService = new RecordingProjectSetupService();
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        RecordingRelayWebSocketClient relayWebSocketClient = new RecordingRelayWebSocketClient();
        DaemonCloudRunner runner = new DaemonCloudRunner(properties, new FakeStateStore(credential()),
                new FakeControlPlaneClient(), relayWebSocketClient, bootstrap, projectSetupService,
                new DaemonRunLifecycle());

        runner.run(new DefaultApplicationArguments("project", "add", "--path=target/project-a"));

        assertThat(projectSetupService.called).isTrue();
        assertThat(bootstrap.isCalled()).isFalse();
        assertThat(relayWebSocketClient.isCalled()).isFalse();
        relayWebSocketClient.shutdown();
    }

    @Test
    void applicationReadyShouldStartCloudFlowAfterSpringStartup() {
        AgentDaemonProperties properties = new AgentDaemonProperties();
        RecordingProjectSetupService projectSetupService = new RecordingProjectSetupService();
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        RecordingRelayWebSocketClient relayWebSocketClient = new RecordingRelayWebSocketClient();
        DaemonRunLifecycle runLifecycle = new DaemonRunLifecycle();
        DaemonCloudRunner runner = new DaemonCloudRunner(properties, new FakeStateStore(credential()),
                new FakeControlPlaneClient(), relayWebSocketClient, bootstrap, projectSetupService, runLifecycle);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("applicationArguments",
                    new DefaultApplicationArguments("--mode=run"));
            context.refresh();

            runner.onApplicationEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context,
                    Duration.ZERO));
        }

        assertThat(bootstrap.isCalled()).isTrue();
        assertThat(relayWebSocketClient.isCalled()).isTrue();
        assertThat(runLifecycle.isRunning()).isTrue();
        runLifecycle.stop();
        relayWebSocketClient.shutdown();
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

        private DeviceCredentialState credential;

        FakeStateStore(DeviceCredentialState credential) {
            super(new ObjectMapper());
            this.credential = credential;
        }

        @Override
        public synchronized Optional<DeviceCredentialState> loadCredential() {
            return Optional.ofNullable(credential);
        }

        @Override
        public synchronized void saveCredential(DeviceCredentialState state) {
            this.credential = state;
        }
    }

    private static class RecordingBootstrap extends DaemonProjectRuntimeBootstrap {

        private final CountDownLatch called = new CountDownLatch(1);

        RecordingBootstrap() {
            super(null, null, null, null, null, null, List.of());
        }

        @Override
        public void bootstrap(DeviceCredentialState credential) {
            called.countDown();
        }

        boolean awaitCalled() throws InterruptedException {
            return called.await(1, TimeUnit.SECONDS);
        }

        boolean isCalled() {
            return called.getCount() == 0;
        }
    }

    private static class RecordingProjectSetupService extends LocalProjectSetupService {

        private volatile boolean called;
        private volatile boolean promptIfEmpty;
        private final CountDownLatch calledLatch = new CountDownLatch(1);

        RecordingProjectSetupService() {
            super(null, null);
        }

        @Override
        public void configureProjects(org.springframework.boot.ApplicationArguments args, boolean promptIfEmpty) {
            this.called = true;
            this.promptIfEmpty = promptIfEmpty;
            this.calledLatch.countDown();
        }

        boolean awaitCalled() throws InterruptedException {
            return calledLatch.await(1, TimeUnit.SECONDS);
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

        boolean isCalled() {
            return called.getCount() == 0;
        }

        void shutdown() {
            scheduler.shutdownNow();
        }
    }

    private static class FakeControlPlaneClient implements ControlPlaneClient {

        private final CountDownLatch paired = new CountDownLatch(1);

        @Override
        public PairDeviceResponse pair(String controlPlaneUrl, PairDeviceRequest request) {
            paired.countDown();
            return new PairDeviceResponse(1L, "dev_smoke", "cred_smoke", "secret");
        }

        boolean awaitPaired() throws InterruptedException {
            return paired.await(1, TimeUnit.SECONDS);
        }

        @Override
        public RelayTicketResponse createDeviceRelayTicket(DeviceCredentialState credential) {
            return null;
        }
    }
}
