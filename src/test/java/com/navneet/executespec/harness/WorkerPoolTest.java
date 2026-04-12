package com.navneet.executespec.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.WorkerAgent;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.tool.GitTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkerPool")
class WorkerPoolTest {

    @Mock private WorkerAgent workerAgent;
    @Mock private StateStore stateStore;
    @Mock private SseBroadcaster broadcaster;
    @Mock private GitTool gitTool;
    @Mock private GitMergeService mergeService;

    private WorkerPool pool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        pool = new WorkerPool(workerAgent, stateStore, mapper, broadcaster, gitTool, mergeService);
    }

    private AgentTask task(String taskId, List<String> dependsOn) {
        String json = buildTaskJson(taskId, dependsOn);
        return AgentTask.builder()
            .taskId(taskId)
            .runId("run-abcdef12")
            .taskJson(json)
            .build();
    }

    private String buildTaskJson(String taskId, List<String> dependsOn) {
        try {
            var node = mapper.createObjectNode();
            node.put("task_id", taskId);
            node.put("title", "Task " + taskId);
            node.put("objective", "Do something");
            node.put("complexity", "LOW");
            var deps = node.putArray("depends_on");
            if (dependsOn != null) dependsOn.forEach(deps::add);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WorkerReport completeReport(String taskId) {
        return WorkerReport.builder().taskId(taskId).status("complete").build();
    }

    @Nested
    @DisplayName("Dependency resolution")
    class DependencyResolution {

        @Test
        @DisplayName("task with no dependencies runs immediately")
        void noDependencies() {
            AgentTask t1 = task("t1", List.of());
            when(workerAgent.execute(any(), any())).thenReturn(completeReport("t1"));
            when(stateStore.lockTask(any(), any(), any())).thenReturn(true);

            WorkerPool.WorkerPoolResult result = pool.runAll(List.of(t1), "run-abcdef12", "model");

            assertThat(result.reports()).hasSize(1);
            assertThat(result.reports().get(0).getTaskId()).isEqualTo("t1");
        }

        @Test
        @DisplayName("dependent task runs after its dependency completes")
        void dependentRunsAfterDependency() {
            AgentTask t1 = task("t1", List.of());
            AgentTask t2 = task("t2", List.of("t1"));

            when(workerAgent.execute(any(), any()))
                .thenAnswer(inv -> {
                    AgentTask t = inv.getArgument(0);
                    return completeReport(t.getTaskId());
                });
            when(stateStore.lockTask(any(), any(), any())).thenReturn(true);

            WorkerPool.WorkerPoolResult result = pool.runAll(List.of(t2, t1), "run-abcdef12", "model");

            assertThat(result.reports()).hasSize(2);
            assertThat(result.reports().stream().map(WorkerReport::getTaskId))
                .containsExactlyInAnyOrder("t1", "t2");
        }

        @Test
        @DisplayName("already-locked task is skipped without error")
        void alreadyLockedSkipped() {
            AgentTask t1 = task("t1", List.of());
            when(stateStore.lockTask(eq("t1"), any(), any())).thenReturn(false);

            WorkerPool.WorkerPoolResult result = pool.runAll(List.of(t1), "run-abcdef12", "model");

            // Locked task is skipped — no reports
            assertThat(result.reports()).isEmpty();
            verify(workerAgent, never()).execute(any(), any());
        }

        @Test
        @DisplayName("worker exception yields BLOCKED report and unblocks dependents")
        void workerExceptionProducesBlockedReport() {
            AgentTask t1 = task("t1", List.of());
            AgentTask t2 = task("t2", List.of("t1"));

            when(stateStore.lockTask(any(), any(), any())).thenReturn(true);
            when(workerAgent.execute(any(), any()))
                .thenAnswer(inv -> {
                    AgentTask t = inv.getArgument(0);
                    if (t != null && "t1".equals(t.getTaskId())) {
                        throw new RuntimeException("provider down");
                    }
                    return completeReport(t != null ? t.getTaskId() : "unknown");
                });

            WorkerPool.WorkerPoolResult result = pool.runAll(List.of(t1, t2), "run-abcdef12", "model");

            assertThat(result.reports()).hasSize(2);
            WorkerReport blockedReport = result.reports().stream()
                .filter(r -> "t1".equals(r.getTaskId())).findFirst().orElseThrow();
            assertThat(blockedReport.getStatus()).isEqualTo("blocked");
            assertThat(blockedReport.getBlockers()).contains("provider down");
        }
    }

    @Nested
    @DisplayName("Thread registry")
    class ThreadRegistry {

        @Test
        @DisplayName("cancelTask on unknown id does not throw")
        void cancelUnknownTask() {
            pool.cancelTask("non-existent-task-id");
        }

        @Test
        @DisplayName("cancelAllTasksForRun delegates to stateStore.getTasksForRun")
        void cancelAllDelegates() {
            AgentTask t1 = task("t1", List.of());
            when(stateStore.getTasksForRun("run-abcdef12")).thenReturn(List.of(t1));

            pool.cancelAllTasksForRun("run-abcdef12");

            verify(stateStore).getTasksForRun("run-abcdef12");
        }
    }

    @Nested
    @DisplayName("resubmitTask")
    class Resubmit {

        @Test
        @DisplayName("resubmitTask runs worker on virtual thread and completes task")
        void resubmitRunsWorker() throws InterruptedException {
            AgentTask t1 = task("t1", List.of());
            when(workerAgent.execute(any(), any())).thenReturn(completeReport("t1"));

            pool.resubmitTask(t1, "run-abcdef12");

            verify(stateStore, timeout(1000)).completeTask(eq("t1"), any());
        }
    }
}
