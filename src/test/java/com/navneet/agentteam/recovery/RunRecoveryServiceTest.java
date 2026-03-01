package com.navneet.agentteam.recovery;

import com.navneet.agentteam.agent.model.WorkerReport;
import com.navneet.agentteam.harness.WorkerPool;
import com.navneet.agentteam.harness.event.PipelineEvent;
import com.navneet.agentteam.sse.SseBroadcaster;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.PipelineRun;
import com.navneet.agentteam.state.repository.RunRecoveryRepository;
import com.navneet.agentteam.tool.FileLockTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunRecoveryService")
class RunRecoveryServiceTest {

    @Mock private StateStore stateStore;
    @Mock private WorkerPool workerPool;
    @Mock private FileLockTool fileLockTool;
    @Mock private RunRecoveryRepository recoveryRepository;
    @Mock private SseBroadcaster sseBroadcaster;
    @Mock private RecoveryProperties props;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RunRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new RunRecoveryService(
            stateStore, workerPool, fileLockTool,
            recoveryRepository, sseBroadcaster, props, eventPublisher
        );
    }

    private PipelineRun run(int autoRestartCount) {
        return PipelineRun.builder()
            .runId("run-abc12345")
            .userRequest("Fix the bug")
            .autoRestartCount(autoRestartCount)
            .build();
    }

    private WorkerReport transientReport(String blocker) {
        return WorkerReport.builder().taskId("t1").blockers(blocker).build();
    }

    @Nested
    @DisplayName("autoRestartIfTransient")
    class AutoRestart {

        @Test
        @DisplayName("returns false when recovery disabled")
        void disabledReturnsFalse() {
            when(props.isEnabled()).thenReturn(false);

            boolean result = service.autoRestartIfTransient(run(0), List.of());
            assertThat(result).isFalse();
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("returns false when max auto-restarts reached")
        void maxRestartsReached() {
            when(props.isEnabled()).thenReturn(true);
            when(props.getMaxAutoRestarts()).thenReturn(3);

            boolean result = service.autoRestartIfTransient(run(3), List.of());
            assertThat(result).isFalse();
            verify(sseBroadcaster).emit(eq("run-abc12345"), any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("returns false when no transient keyword in blockers")
        void noTransientKeyword() {
            when(props.isEnabled()).thenReturn(true);
            when(props.getMaxAutoRestarts()).thenReturn(3);

            WorkerReport report = transientReport("logic error in service layer");
            boolean result = service.autoRestartIfTransient(run(0), List.of(report));
            assertThat(result).isFalse();
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("returns false when all reports have null blockers")
        void nullBlockers() {
            when(props.isEnabled()).thenReturn(true);
            when(props.getMaxAutoRestarts()).thenReturn(3);

            WorkerReport report = WorkerReport.builder().taskId("t1").blockers(null).build();
            boolean result = service.autoRestartIfTransient(run(0), List.of(report));
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("schedules restart on 'rate limit' keyword")
        void rateLimitKeyword() throws InterruptedException {
            when(props.isEnabled()).thenReturn(true);
            when(props.getMaxAutoRestarts()).thenReturn(3);
            when(props.getAutoRestartBackoffSeconds()).thenReturn(0); // no sleep in tests

            WorkerReport report = transientReport("rate limit exceeded for model");
            boolean result = service.autoRestartIfTransient(run(0), List.of(report));
            assertThat(result).isTrue();

            // Give virtual thread time to publish event (backoff=0 so instant)
            Thread.sleep(200);
            ArgumentCaptor<PipelineEvent> captor = ArgumentCaptor.forClass(PipelineEvent.class);
            verify(eventPublisher, timeout(500)).publishEvent(captor.capture());
            assertThat(captor.getValue()).isInstanceOf(PipelineEvent.RestartRun.class);
        }

        @Test
        @DisplayName("schedules restart on '429' keyword")
        void http429Keyword() throws InterruptedException {
            when(props.isEnabled()).thenReturn(true);
            when(props.getMaxAutoRestarts()).thenReturn(3);
            when(props.getAutoRestartBackoffSeconds()).thenReturn(0);

            WorkerReport report = transientReport("HTTP 429 Too Many Requests");
            boolean result = service.autoRestartIfTransient(run(1), List.of(report));
            assertThat(result).isTrue();

            verify(recoveryRepository, timeout(500)).save(any());
        }

        @Test
        @DisplayName("schedules restart on 'service unavailable' keyword")
        void serviceUnavailable() throws InterruptedException {
            when(props.isEnabled()).thenReturn(true);
            when(props.getMaxAutoRestarts()).thenReturn(3);
            when(props.getAutoRestartBackoffSeconds()).thenReturn(0);

            WorkerReport report = transientReport("service unavailable — try again later");
            boolean result = service.autoRestartIfTransient(run(0), List.of(report));
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("RestartRun event carries incremented autoRestartCount")
        void restartCountIncremented() throws InterruptedException {
            when(props.isEnabled()).thenReturn(true);
            when(props.getMaxAutoRestarts()).thenReturn(5);
            when(props.getAutoRestartBackoffSeconds()).thenReturn(0);

            WorkerReport report = transientReport("all providers unavailable");
            service.autoRestartIfTransient(run(2), List.of(report));

            ArgumentCaptor<PipelineEvent> captor = ArgumentCaptor.forClass(PipelineEvent.class);
            verify(eventPublisher, timeout(500)).publishEvent(captor.capture());

            PipelineEvent.RestartRun event = (PipelineEvent.RestartRun) captor.getValue();
            assertThat(event.autoRestartCount()).isEqualTo(3); // 2 + 1
        }
    }
}
