package com.navneet.executespec.recovery;

import com.navneet.executespec.state.entity.AgentTask;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StuckRunEvaluator")
class StuckRunEvaluatorTest {

    @Mock private RecoveryProperties props;

    private StuckRunEvaluator evaluator;

    private AgentTask task(String taskId) {
        return AgentTask.builder().taskId(taskId).runId("run-1").build();
    }

    @BeforeEach
    void setUp() {
        evaluator = new StuckRunEvaluator(props);
    }

    @Nested
    @DisplayName("LLM_TIMEOUT")
    class LlmTimeout {

        @Test
        @DisplayName("last event LLM_REQUEST_SENT → LLM_TIMEOUT")
        void llmRequestSent() {
            var result = evaluator.evaluate(task("t1"),
                List.of("TOOL_CALL:readFile", "LLM_REQUEST_SENT"));
            assertThat(result.classification()).isEqualTo(StuckClassification.LLM_TIMEOUT);
        }

        @Test
        @DisplayName("last event 'Calling LLM' → LLM_TIMEOUT")
        void callingLlm() {
            var result = evaluator.evaluate(task("t1"),
                List.of("Calling LLM", "Calling LLM"));
            assertThat(result.classification()).isEqualTo(StuckClassification.LLM_TIMEOUT);
        }
    }

    @Nested
    @DisplayName("FILE_LOCK_BLOCK")
    class FileLockBlock {

        @Test
        @DisplayName("all recent events are FILE_LOCK_DENIED → FILE_LOCK_BLOCK")
        void fileLockBlock() {
            var result = evaluator.evaluate(task("t1"), List.of(
                "TOOL_CALL:writeFile",
                "FILE_LOCK_DENIED",
                "FILE_LOCK_DENIED",
                "FILE_LOCK_DENIED",
                "FILE_LOCK_DENIED",
                "FILE_LOCK_DENIED"
            ));
            assertThat(result.classification()).isEqualTo(StuckClassification.FILE_LOCK_BLOCK);
        }

        @Test
        @DisplayName("lock denied but progress after → not FILE_LOCK_BLOCK")
        void lockDeniedButProgress() {
            var result = evaluator.evaluate(task("t1"), List.of(
                "FILE_LOCK_DENIED",
                "TOOL_CALL:writeFile",
                "COMMITTED",
                "TOOL_CALL:readFile",
                "TOOL_CALL:writeFile",
                "LLM_REQUEST_SENT"
            ));
            // Last event is LLM_REQUEST_SENT so classified as LLM_TIMEOUT
            assertThat(result.classification()).isNotEqualTo(StuckClassification.FILE_LOCK_BLOCK);
        }
    }

    @Nested
    @DisplayName("LLM_LOOP")
    class LlmLoop {

        @Test
        @DisplayName("same tool called 3+ times in window → LLM_LOOP")
        void toolLoop() {
            when(props.getEvaluatorLogWindow()).thenReturn(20);
            when(props.getLoopRepeatThreshold()).thenReturn(3);

            var result = evaluator.evaluate(task("t1"), List.of(
                "TOOL_CALL:readFile",
                "TOOL_CALL:readFile",
                "TOOL_CALL:readFile",
                "TOOL_CALL:readFile"
            ));
            assertThat(result.classification()).isEqualTo(StuckClassification.LLM_LOOP);
            assertThat(result.detail()).contains("readFile");
        }

        @Test
        @DisplayName("tool called 2 times below threshold → not LLM_LOOP")
        void belowThreshold() {
            when(props.getEvaluatorLogWindow()).thenReturn(20);
            when(props.getLoopRepeatThreshold()).thenReturn(3);

            var result = evaluator.evaluate(task("t1"), List.of(
                "TOOL_CALL:readFile",
                "TOOL_CALL:writeFile",
                "TOOL_CALL:readFile",
                "BUILD_FAILED"
            ));
            // Build failure without commit → BUILD_FAILURE_STALL
            assertThat(result.classification()).isNotEqualTo(StuckClassification.LLM_LOOP);
        }
    }

    @Nested
    @DisplayName("BUILD_FAILURE_STALL")
    class BuildFailureStall {

        @Test
        @DisplayName("BUILD_FAILED without commit → BUILD_FAILURE_STALL")
        void buildFailedNoCommit() {
            when(props.getEvaluatorLogWindow()).thenReturn(20);
            when(props.getLoopRepeatThreshold()).thenReturn(3);

            var result = evaluator.evaluate(task("t1"), List.of(
                "TOOL_CALL:writeFile",
                "BUILD_FAILED",
                "TOOL_CALL:readFile",
                "TEST_FAILED"
            ));
            assertThat(result.classification()).isEqualTo(StuckClassification.BUILD_FAILURE_STALL);
        }

        @Test
        @DisplayName("BUILD_FAILED then committed → not stall")
        void buildFailedThenCommit() {
            // Last event is LLM_REQUEST_SENT — LLM_TIMEOUT path taken before loop/build checks
            var result = evaluator.evaluate(task("t1"), List.of(
                "BUILD_FAILED",
                "TOOL_CALL:writeFile",
                "git_commit success",
                "COMMITTED",
                "LLM_REQUEST_SENT"
            ));
            assertThat(result.classification()).isEqualTo(StuckClassification.LLM_TIMEOUT);
        }
    }

    @Nested
    @DisplayName("UNKNOWN")
    class Unknown {

        @Test
        @DisplayName("empty events → UNKNOWN")
        void emptyEvents() {
            var result = evaluator.evaluate(task("t1"), List.of());
            assertThat(result.classification()).isEqualTo(StuckClassification.UNKNOWN);
        }

        @Test
        @DisplayName("unrecognised events → UNKNOWN")
        void unrecognisedEvents() {
            when(props.getEvaluatorLogWindow()).thenReturn(20);
            when(props.getLoopRepeatThreshold()).thenReturn(3);

            // No TOOL_CALL:, no LLM_REQUEST_SENT, no BUILD_FAILED, no lock events
            var result = evaluator.evaluate(task("t1"), List.of(
                "STATUS_UPDATE: working",
                "STATUS_UPDATE: working"
            ));
            assertThat(result.classification()).isEqualTo(StuckClassification.UNKNOWN);
        }

        @Test
        @DisplayName("detail includes task id on empty events")
        void detailIncludesTaskId() {
            var result = evaluator.evaluate(task("my-task-99"), List.of());
            assertThat(result.detail()).contains("my-task-99");
        }
    }
}
