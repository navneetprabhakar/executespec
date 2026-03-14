package com.navneet.executespec.deployment.preview;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.config.AgentProperties;
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

    @Mock ComposeExecutor compose;
    @Mock DockerExecutor docker;
    @Mock DevDockerfileGenerator dockerfileGen;
    @Mock ComposeFileGenerator composeFileGen;
    @Mock DatabaseDetector databaseDetector;
    @Mock EnvVarDetector envVarDetector;
    @Mock PreviewPortPool portPool;
    @Mock SseBroadcaster broadcaster;
    @Mock StateStore stateStore;

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

        // Make virtual threads from buildAndStart() exit quickly (returns build failure immediately)
        // Without this, docker.build() returns null (Mockito default) → NPE in virtual thread → race with @TempDir cleanup
        when(docker.build(any(), any())).thenReturn(
                new DockerExecutor.DockerBuildResult(false, null, "mock-build", 0));

        service = new PreviewService(compose, docker, dockerfileGen, composeFileGen,
                databaseDetector, envVarDetector, portPool, props, broadcaster, stateStore, agentProps);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Virtual threads spawned by service.start() write Dockerfile/.dockerignore to workspace dirs.
        // Wait for them to finish before JUnit @TempDir cleanup, otherwise cleanup fails
        // because the files are still being written.
        Thread.sleep(300);
    }

    private AppUser freeUser() {
        return AppUser.builder().id("user-1").tier("FREE")
                .firstName("Test").lastName("User").email("test@test.com")
                .passwordHash("x").build();
    }

    private AppUser proUser() {
        return AppUser.builder().id("user-1").tier("PRO")
                .firstName("Test").lastName("User").email("test@test.com")
                .passwordHash("x").build();
    }

    private AppUser enterpriseUser() {
        return AppUser.builder().id("user-1").tier("ENTERPRISE")
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
            service = new PreviewService(compose, docker, dockerfileGen, composeFileGen,
                databaseDetector, envVarDetector, portPool, props, broadcaster, stateStore, agentProps);

            assertThatThrownBy(() -> service.start("run-1", freeUser()))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("disabled");
        }

        @Test
        @DisplayName("Throws when Docker is not available")
        void throwsWhenNoDocker() {
            when(docker.isDockerAvailable()).thenReturn(false);

            assertThatThrownBy(() -> service.start("run-1", freeUser()))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("Docker");
        }

        @Test
        @DisplayName("Throws when run does not exist")
        void throwsWhenRunNotFound() {
            when(docker.isDockerAvailable()).thenReturn(true);
            when(stateStore.findRun("run-1")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.start("run-1", freeUser()))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Throws when run is still WORKING (not complete)")
        void throwsWhenRunNotComplete() {
            when(docker.isDockerAvailable()).thenReturn(true);
            when(stateStore.findRun("run-1")).thenReturn(Optional.of(workingRun("run-1")));

            assertThatThrownBy(() -> service.start("run-1", freeUser()))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("must be complete");
        }

        @Test
        @DisplayName("Accepts PARTIAL status for preview")
        void acceptsPartialStatus() throws IOException {
            when(docker.isDockerAvailable()).thenReturn(true);
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
            when(docker.isDockerAvailable()).thenReturn(true);
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
            when(docker.isDockerAvailable()).thenReturn(true);

            // First preview — should succeed
            String runId1 = "run-1";
            when(stateStore.findRun(runId1)).thenReturn(Optional.of(completedRun(runId1)));
            Path ws1 = workspaceRoot.resolve(runId1);
            Files.createDirectories(ws1);
            when(dockerfileGen.detect(ws1)).thenReturn(PreviewProjectType.NODE_FRONTEND);
            when(portPool.allocate(runId1)).thenReturn(10000);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM alpine\n");

            PreviewSession s1 = service.start(runId1, freeUser());
            assertThat(s1).isNotNull();

            // Wait for virtual thread from first start() to finish touching mocks
            // (it calls dockerIgnore/generate/build on the mock — concurrent access
            //  while stubbing new mocks causes WrongTypeOfReturnValue)
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
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);
            when(portPool.allocate(runId)).thenReturn(10000);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM alpine\n");

            PreviewSession s1 = service.start(runId, freeUser());
            PreviewSession s2 = service.start(runId, freeUser()); // idempotent

            assertThat(s2.runId()).isEqualTo(s1.runId());
            assertThat(s2.hostPort()).isEqualTo(s1.hostPort());
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
        @DisplayName("Stop cleans up Docker container, port, and workspace files")
        void stopCleansUp() throws IOException {
            // Start a preview first
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);
            when(portPool.allocate(runId)).thenReturn(10000);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM alpine\n");

            service.start(runId, freeUser());

            // Now stop it
            service.stop(runId);

            // Verify cleanup
            verify(docker).stop(any()); // docker stop called
            verify(portPool).release(10000); // port released
            assertThat(service.getStatus(runId)).isNull(); // session removed
        }

        @Test
        @DisplayName("Stop emits PREVIEW_STOPPED SSE event")
        void stopEmitsEvent() throws IOException {
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);
            when(portPool.allocate(runId)).thenReturn(10000);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM alpine\n");

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
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);
            when(portPool.allocate(runId)).thenReturn(10000);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM alpine\n");

            service.start(runId, freeUser());

            PreviewSession session = service.getStatus(runId);
            assertThat(session).isNotNull();
            assertThat(session.runId()).isEqualTo(runId);
            assertThat(session.projectType()).isEqualTo(PreviewProjectType.NODE_FRONTEND);
            assertThat(session.hostPort()).isEqualTo(10000);
        }
    }

    // ── Initial session state ───────────────────────────────────────────────

    @Nested
    @DisplayName("Initial session properties")
    class InitialSession {

        @Test
        @DisplayName("Initial session status is BUILDING")
        void initialStatusIsBuilding() throws IOException {
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);
            when(portPool.allocate(runId)).thenReturn(10000);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM alpine\n");

            PreviewSession session = service.start(runId, freeUser());
            assertThat(session.status()).isEqualTo(PreviewStatus.BUILDING);
        }

        @Test
        @DisplayName("URL uses localhost when proxy is disabled")
        void urlWithoutProxy() throws IOException {
            props.getProxy().setEnabled(false);
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);
            when(portPool.allocate(runId)).thenReturn(10042);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM alpine\n");

            PreviewSession session = service.start(runId, freeUser());
            assertThat(session.url()).isEqualTo("http://localhost:10042");
        }

        @Test
        @DisplayName("URL uses proxy base URL when proxy is enabled")
        void urlWithProxy() throws IOException {
            props.getProxy().setEnabled(true);
            props.getProxy().setBaseUrl("https://preview.executespec.ai");
            service = new PreviewService(compose, docker, dockerfileGen, composeFileGen,
                databaseDetector, envVarDetector, portPool, props, broadcaster, stateStore, agentProps);

            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-1";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);
            when(portPool.allocate(runId)).thenReturn(10042);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM alpine\n");

            PreviewSession session = service.start(runId, freeUser());
            assertThat(session.url()).isEqualTo("https://preview.executespec.ai/preview/run-1");
        }

        @Test
        @DisplayName("expiresAt reflects tier TTL — FREE=15min, PRO=60min, ENTERPRISE=240min")
        void expiresAtReflectsTier() throws IOException {
            when(docker.isDockerAvailable()).thenReturn(true);

            for (var entry : new Object[][]{
                    {"FREE", freeUser(), 15},
                    {"PRO", proUser(), 60},
                    {"ENTERPRISE", enterpriseUser(), 240}
            }) {
                String tier = (String) entry[0];
                AppUser user = (AppUser) entry[1];
                int minutes = (int) entry[2];

                String runId = "run-" + tier;
                when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

                Path ws = workspaceRoot.resolve(runId);
                Files.createDirectories(ws);
                when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);
                when(portPool.allocate(runId)).thenReturn(10000 + minutes);
                when(dockerfileGen.dockerIgnore()).thenReturn("");
                when(dockerfileGen.generate(any(), any())).thenReturn("FROM alpine\n");

                PreviewSession session = service.start(runId, user);

                long ttlMinutes = java.time.Duration.between(session.startedAt(), session.expiresAt()).toMinutes();
                assertThat(ttlMinutes).as("TTL for tier " + tier).isEqualTo(minutes);

                // Cleanup for next iteration
                service.stop(runId);
            }
        }
    }

    // ── Multi project type support ──────────────────────────────────────────

    @Nested
    @DisplayName("All project types")
    class AllProjectTypes {

        @Test
        @DisplayName("Start accepts NODE_BACKEND type")
        void startsNodeBackend() throws IOException {
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-backend";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_BACKEND);
            when(portPool.allocate(runId)).thenReturn(10001);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM node:20-alpine\n");

            PreviewSession session = service.start(runId, freeUser());
            assertThat(session.projectType()).isEqualTo(PreviewProjectType.NODE_BACKEND);
            assertThat(session.status()).isEqualTo(PreviewStatus.BUILDING);
        }

        @Test
        @DisplayName("Start accepts SPRING_BOOT type")
        void startsSpringBoot() throws IOException {
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-spring";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.SPRING_BOOT);
            when(portPool.allocate(runId)).thenReturn(10002);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM eclipse-temurin:21\n");

            PreviewSession session = service.start(runId, freeUser());
            assertThat(session.projectType()).isEqualTo(PreviewProjectType.SPRING_BOOT);
        }

        @Test
        @DisplayName("Start accepts PYTHON type")
        void startsPython() throws IOException {
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-python";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.PYTHON);
            when(portPool.allocate(runId)).thenReturn(10003);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM python:3.12-slim\n");

            PreviewSession session = service.start(runId, freeUser());
            assertThat(session.projectType()).isEqualTo(PreviewProjectType.PYTHON);
        }

        @Test
        @DisplayName("Each project type maps to the correct container port")
        void containerPortPerType() {
            // Verify enum container ports directly — the session's projectType is set
            // from what detect() returns, so the enum is the source of truth
            assertThat(PreviewProjectType.NODE_FRONTEND.getContainerPort()).isEqualTo(5173);
            assertThat(PreviewProjectType.NODE_BACKEND.getContainerPort()).isEqualTo(3000);
            assertThat(PreviewProjectType.SPRING_BOOT.getContainerPort()).isEqualTo(8080);
            assertThat(PreviewProjectType.PYTHON.getContainerPort()).isEqualTo(8000);
            assertThat(PreviewProjectType.FULL_STACK.getContainerPort()).isEqualTo(5173);
        }
    }

    // ── Workspace file lifecycle ────────────────────────────────────────────

    @Nested
    @DisplayName("Workspace file management")
    class WorkspaceFiles {

        @Test
        @DisplayName("Start invokes dockerfileGen.generate and dockerIgnore")
        void startCallsGenerator() throws IOException {
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-files";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);
            when(portPool.allocate(runId)).thenReturn(10020);
            when(dockerfileGen.dockerIgnore()).thenReturn(".git\nnode_modules\n");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM node:20-alpine\nCMD npm start\n");

            service.start(runId, freeUser());

            // buildAndStart runs on a virtual thread and calls generate + dockerIgnore
            // Verify they were called (verify will wait up to 1s by default)
            verify(dockerfileGen, timeout(2000)).generate(eq(PreviewProjectType.NODE_FRONTEND), eq(ws));
            verify(dockerfileGen, timeout(2000)).dockerIgnore();
        }

        @Test
        @DisplayName("Stop deletes Dockerfile and .dockerignore if they exist")
        void stopDeletesFiles() throws IOException {
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-cleanup";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);
            when(portPool.allocate(runId)).thenReturn(10021);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM alpine\n");

            service.start(runId, freeUser());

            // Manually create files to simulate what buildAndStart does
            Files.writeString(ws.resolve("Dockerfile"), "FROM alpine\n");
            Files.writeString(ws.resolve(".dockerignore"), "");

            service.stop(runId);
            assertThat(ws.resolve("Dockerfile")).doesNotExist();
            assertThat(ws.resolve(".dockerignore")).doesNotExist();
        }
    }

    // ── SSE event emission ──────────────────────────────────────────────────

    @Nested
    @DisplayName("SSE event emission on start")
    class SseEvents {

        @Test
        @DisplayName("Start emits PREVIEW_BUILDING event on virtual thread")
        void startEmitsBuildingEvent() throws IOException {
            when(docker.isDockerAvailable()).thenReturn(true);
            String runId = "run-sse";
            when(stateStore.findRun(runId)).thenReturn(Optional.of(completedRun(runId)));

            Path ws = workspaceRoot.resolve(runId);
            Files.createDirectories(ws);
            when(dockerfileGen.detect(ws)).thenReturn(PreviewProjectType.NODE_FRONTEND);
            when(portPool.allocate(runId)).thenReturn(10030);
            when(dockerfileGen.dockerIgnore()).thenReturn("");
            when(dockerfileGen.generate(any(), any())).thenReturn("FROM alpine\n");

            service.start(runId, freeUser());

            // PREVIEW_BUILDING is emitted from the virtual thread — wait for it
            ArgumentCaptor<RunEvent> captor = ArgumentCaptor.forClass(RunEvent.class);
            verify(broadcaster, timeout(2000).atLeast(2)).emit(eq(runId), captor.capture());

            boolean hasBuildingEvent = captor.getAllValues().stream()
                    .anyMatch(e -> "PREVIEW_BUILDING".equals(e.type()));
            assertThat(hasBuildingEvent).isTrue();
        }
    }
}
