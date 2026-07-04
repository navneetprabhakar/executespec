package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.engine.WorkerResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** task 12 — run-journal entry + block rendering (flag execution.run-journal.enabled). */
class SequentialExecutorRunJournalTest {

  private static PlannedTask task(String id, String title) {
    return PlannedTask.builder().id(id).title(title).description("d").build();
  }

  private static TaskOutcome outcome(String taskId, String title, String summary) {
    return new TaskOutcome(
        taskId,
        title,
        TaskOutcome.Status.SUCCESS,
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED)
            .completionSummary(summary)
            .build(),
        false, null, true, 0, null, 100L, 5, "deepseek", "m", null, null, null, null, null);
  }

  @Test
  void entryCarriesIdTitleStatusAndTruncatedSummary() {
    String e =
        SequentialExecutor.journalEntry(
            task("T1", "Add auth"), outcome("T1", "Add auth", "Added JWT auth with refresh"));
    assertThat(e).contains("T1").contains("Add auth").contains("SUCCESS").contains("JWT auth");

    String longSummary = "x".repeat(500);
    String e2 =
        SequentialExecutor.journalEntry(
            task("T2", "Big"), outcome("T2", "Big", longSummary));
    assertThat(e2.length()).isLessThan(300);
    assertThat(e2).contains("…");
  }

  @Test
  void blockShowsPlanOutlineAndLastFiveEntries() {
    Plan plan =
        Plan.builder()
            .tasks(List.of(task("T1", "One"), task("T2", "Two"), task("T3", "Three")))
            .build();
    List<String> journal =
        List.of("T1 (One) → SUCCESS", "T2 (Two) → SUCCESS", "T3 (Three) → WORKER_FAILED",
            "T4 → SUCCESS", "T5 → SUCCESS", "T6 → SUCCESS", "T7 → SUCCESS");
    String block = SequentialExecutor.journalBlock(plan, journal);
    assertThat(block).contains("RUN JOURNAL");
    assertThat(block).contains("T1 One");
    // only the LAST five journal entries appear
    assertThat(block).doesNotContain("T1 (One)");
    assertThat(block).doesNotContain("T2 (Two)");
    assertThat(block).contains("T3 (Three)").contains("T7");
    assertThat(block).contains("Stay consistent");
  }
}
