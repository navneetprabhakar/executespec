package com.navneet.executespec.deployment.preview;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PreviewService")
class PreviewServiceTest {

    @Mock DevDockerfileGenerator dockerfileGen;
    @Mock DatabaseDetector databaseDetector;
    @Mock EnvVarDetector envVarDetector;
    @Mock ComposeFileGenerator composeFileGen;
    @Mock ComposeExecutor composeExecutor;
    @Mock SseBroadcaster broadcaster;
    @Mock StateStore stateStore;
    @Mock TierConfigRepository tierConfigRepo;
    @Mock WorkspaceRestoreService restoreService;
    io.micrometer.core.instrument.MeterRegistry meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    PreviewPortPool portPool;

    PreviewProperties props;
    AgentProperties agentProps;
    PreviewService service;

    @TempDir Path workspaceRoot;

    @BeforeEach
    void setUp() {
        props = new PreviewProperties();
        props.setEnabled(true);

        agentProps = new AgentProperties();
        agentProps.setWorkspace(workspaceRoot.toString());

        portPool = new PreviewPortPool(props);

        // Default: tier config returns unlimited preview starts (don't block tests)
        TierConfig freeTier = new TierConfig();
        freeTier.setMaxPreviewStarts(-1);
        when(tierConfigRepo.findById(any())).thenReturn(Optional.of(freeTier));

        // Default: restoreService returns workspace path (workspace exists locally)
        when(restoreService.ensureWorkspaceAvailable(any())).thenAnswer(inv -> {
            String runId = inv.getArgument(0);
            return workspaceRoot.resolve(runId);
        });

        // Default: preview mode is WEB
        when(dockerfileGen.detectMode(any(), any())).thenReturn(PreviewMode.REST);

        service = new PreviewService(dockerfileGen, databaseDetector, envVarDetector,
                composeFileGen, composeExecutor, portPool, props, broadcaster, stateStore, agentProps, tierConfigRepo, restoreService, meterRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Virtual threads spawned by service.start() run buildPushAndDeploy.
        // Wait for them to finish before JUnit @TempDir cleanup.
        Thread.sleep(300);
    }

    private AppUser freeUser() {
        return AppUser.builder().id("user-1").tier("FREE")
                .firstName("Test").lastName("User").email("test@test.com")
                .passwordHash("x").build();
    }

    private PipelineRun completedRun(String runId) {
        return PipelineRun.builder().runId(runId).status("COMPLETE").createdByUserId("user-1").build();
    }

    private PipelineRun partialRun(String runId) {
        return PipelineRun.builder().runId(runId).status("PARTIAL").createdByUserId("user-1").build();
    }

    private PipelineRun workingRun(String runId) {
        return PipelineRun.builder().runId(runId).status("WORKING").createdByUserId("user-1").build();
    }

    // ── Guards / validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Start guards")
    class StartGuards {

        @Test
        @DisplayName("Throws when preview is disabled")
        void throwsWhenDisabled() {
            props.setEnabled(false);
            service = new PreviewService(dockerfileGen, databaseDetector, envVarDetector,
                    composeFileGen, composeExecutor, portPool, props, broadcaster, stateStore, agentProps, tierConfigRepo, restoreService, meterRegistry);

            assertThatThrownBy(() -> service.start("run-1", freeUser()))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("disabled");
        }

        @Test
        @DisplayName("Throws when run does not exist")
        void throwsWhenRunNotFound() {
            when(stateStore.findRun("run-1")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.start("run-1", freeUser()))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Throws when run is PLANNING (not in preview-allowed statuses)")
        void throwsWhenRunNotComplete() {
            PipelineRun planningRun = PipelineRun.builder().runId("run-1").status("PLANNING").createdByUserId("user-1").build();
            when(stateStore.findRun("run-1")).thenReturn(Optional.of(planningRun));

            assertThatThrownBy(() -> service.start("run-1", freeUser()))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("must be complete");
        }

        @Test
        @DisplayName("Accepts PARTIAL status for preview")
        void acceptsPartialStatus() throws IOException {
            when(stateStore.findRun("run-1")).thenReturn(Optional.of(partialRun("run-1")));

            // Create workspace dir
            Path ws = workspaceRoot.resolve("run-1");
            Files.createDirectories(ws);

            when(dockerfileGen.detect(ws)).thenReturn(null); // unsupported

            assertThatThrownBy(() -> service.start("run-1", freeUser()))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("Unsupported project type");
        }

        @Test
        @DisplayName("Throws when unsupported project type detected")
        void throwsWhenUnsupportedType() throws IOException {
            when(stateStore.findRun("run-1")).thenReturn(Optional.of(completedRun("run-1")));

            Path ws = workspaceRoot.resolve("run-1");
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(null);

            assertThatThrownBy(() -> service.start("run-1", freeUser()))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("Unsupported project type");
        }
    }

    // ── Concurrent preview limits ───────────────────────────────────────────

    @Nested
    @DisplayName("Concurrent preview limits by tier")
    class ConcurrencyLimits {

        @Test
        @DisplayName("FREE tier allows 1 concurrent preview")
        void freeTierLimit() throws Exception {
            // First preview — should succeed
            String runId1 = "run-1";
            when(stateStore.findRun(runId1)).thenReturn(Optional.of(completedRun(runId1)));
            Path ws1 = workspaceRoot.resolve(runId1);
            Files.createDirectories(ws1);
            when(dockerfileGen.detect(ws1)).thenReturn(PreviewProjectType.NODE_FRONTEND);

            PreviewSession s1 = service.start(runId1, freeUser());
            assertThat(s1).isNotNull();

            // Wait for virtual thread to settle
            Thread.sleep(500);

            // Second preview — should fail (FREE = 1 max)
            String runId2 = "run-2";
            when(stateStore.findRun(runId2)).thenReturn(Optional.of(completedRun(runId2)));
            Path ws2 = workspaceRoot.resolve(runId2);
            Files.createDirectories(ws2);
            when(dockerfileGen.detect(ws2)).thenReturn(PreviewProjectType.NODE_FRONTEND);

            assertThatThrownBy(() -> service.start(runId2, freeUser()))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("Max concurrent previews");
        }
    }

    // ── Idempotency ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Idempotent start")
    class Idempotency {

        @Test
        @DisplayName("Starting same run twice returns existing session")
        void startSameRunTwice() throws IOException {
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);

            PreviewSession s1 = service.start(runId, freeUser());
            PreviewSession s2 = service.start(runId, freeUser()); // idempotent

            assertThat(s2.runId()).isEqualTo(s1.runId());
            assertThat(s2.composeProjectName()).isEqualTo(s1.composeProjectName());
        }
    }

    // ── Stop ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Stop")
    class Stop {

        @Test
        @DisplayName("Stop non-existent preview is a no-op")
        void stopNonExistent() {
            // Should not throw
            service.stop("run-does-not-exist");
        }

        @Test
        @DisplayName("Stop removes session from active map")
        void stopCleansUp() throws IOException {
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);

            service.start(runId, freeUser());

            // Now stop it
            service.stop(runId);

            // Session removed
            assertThat(service.getStatus(runId)).isNull();
        }

        @Test
        @DisplayName("Stop emits PREVIEW_STOPPED SSE event")
        void stopEmitsEvent() throws IOException {
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);

            service.start(runId, freeUser());
            service.stop(runId);

            // Verify PREVIEW_STOPPED event emitted
            ArgumentCaptor<RunEvent> captor = ArgumentCaptor.forClass(RunEvent.class);
            verify(broadcaster, atLeastOnce()).emit(eq(runId), captor.capture());

            boolean hasStoppedEvent = captor.getAllValues().stream()
                    .anyMatch(e -> "PREVIEW_STOPPED".equals(e.type()));
            assertThat(hasStoppedEvent).isTrue();
        }
    }

    // ── Status & Logs ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Status and Logs")
    class StatusAndLogs {

        @Test
        @DisplayName("getStatus returns null for unknown run")
        void statusNull() {
            assertThat(service.getStatus("unknown")).isNull();
        }

        @Test
        @DisplayName("getLogs returns empty for unknown run")
        void logsEmpty() {
            assertThat(service.getLogs("unknown", 50, null)).isEmpty();
        }

        @Test
        @DisplayName("getStatus returns session after start")
        void statusAfterStart() throws IOException {
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);

            service.start(runId, freeUser());

            PreviewSession session = service.getStatus(runId);
            assertThat(session).isNotNull();
            assertThat(session.runId()).isEqualTo(runId);
            assertThat(session.projectType()).isEqualTo(PreviewProjectType.NODE_FRONTEND);
        }
    }

    // ── Initial session state ───────────────────────────────────────────────

    @Nested
    @DisplayName("Initial session properties")
    class InitialSession {

        @Test
        @DisplayName("Initial session status is ANALYZING")
        void initialStatusIsAnalyzing() throws IOException {
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);

            PreviewSession session = service.start(runId, freeUser());
            assertThat(session.status()).isEqualTo(PreviewStatus.ANALYZING);
        }

        @Test
        @DisplayName("Session has valid expiresAt based on TTL config")
        void sessionHasExpiresAt() throws IOException {
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);

            PreviewSession session = service.start(runId, freeUser());
            assertThat(session.expiresAt()).isNotNull();

            long ttlMinutes = java.time.Duration.between(session.startedAt(), session.expiresAt()).toMinutes();
            assertThat(ttlMinutes).isEqualTo(props.getPreviewTtlMinutes());
        }
    }

    // ── Multi project type support ──────────────────────────────────────────

    @Nested
    @DisplayName("All project types")
    class AllProjectTypes {

        @Test
        @DisplayName("Start accepts NODE_BACKEND type")
        void startsNodeBackend() throws IOException {
            String runId = "run-backend";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_BACKEND);

            PreviewSession session = service.start(runId, freeUser());
            assertThat(session.projectType()).isEqualTo(PreviewProjectType.NODE_BACKEND);
            assertThat(session.status()).isEqualTo(PreviewStatus.ANALYZING);
        }

        @Test
        @DisplayName("Start accepts SPRING_BOOT type")
        void startsSpringBoot() throws IOException {
            String runId = "run-spring";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.SPRING_BOOT);

            PreviewSession session = service.start(runId, freeUser());
            assertThat(session.projectType()).isEqualTo(PreviewProjectType.SPRING_BOOT);
        }

        @Test
        @DisplayName("Start accepts PYTHON type")
        void startsPython() throws IOException {
            String runId = "run-python";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.PYTHON);

            PreviewSession session = service.start(runId, freeUser());
            assertThat(session.projectType()).isEqualTo(PreviewProjectType.PYTHON);
        }

        @Test
        @DisplayName("Each project type maps to the correct container port")
        void containerPortPerType() {
            assertThat(PreviewProjectType.NODE_FRONTEND.getContainerPort()).isEqualTo(5173);
            assertThat(PreviewProjectType.NODE_BACKEND.getContainerPort()).isEqualTo(3000);
            assertThat(PreviewProjectType.SPRING_BOOT.getContainerPort()).isEqualTo(8080);
            assertThat(PreviewProjectType.PYTHON.getContainerPort()).isEqualTo(8000);
            assertThat(PreviewProjectType.FULL_STACK.getContainerPort()).isEqualTo(5173);
        }
    }

    // ── Per-run preview start limits ────────────────────────────────────────

    @Nested
    @DisplayName("Per-run preview start limits")
    class PreviewStartLimits {

        @Test
        @DisplayName("Throws when per-run preview limit is reached")
        void throwsWhenLimitReached() throws IOException {
            // Configure tier to allow max 3 starts
            TierConfig config = new TierConfig();
            config.setMaxPreviewStarts(3);
            when(tierConfigRepo.findById("FREE")).thenReturn(Optional.of(config));

            // Run already has 3 starts
            PipelineRun run = PipelineRun.builder()
                    .runId("run-limited").status("COMPLETE").createdByUserId("user-1")
                    .previewStartCount(3).build();
            when(stateStore.findRun("run-limited")).thenReturn(Optional.of(run));

            Path ws = workspaceRoot.resolve("run-limited");
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);

            assertThatThrownBy(() -> service.start("run-limited", freeUser()))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("Preview limit reached");
        }
    }
}
