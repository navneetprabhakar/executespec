package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.junit.jupiter.api.Test;

class ContinuationEscalationBudgetTest {
  @Test
  void crashRecoveryCountsReservedSameRouteAndProviderBudgets() {
    AgentTaskRepository tasks = mock(AgentTaskRepository.class);
    AgentTask task = new AgentTask();
    task.setTaskId("12345678_T1");
    task.setContinuationActionsJson(
        """
        [{"status":"RESERVED","decision":"CONTINUE_SAME_MODEL_PROVIDER"},
         {"status":"EXECUTED","decision":"CHANGE_PROVIDER"},
         {"status":"SKIPPED","decision":"STEP_UP_MODEL"}]
        """);
    when(tasks.findByRunId("12345678-run")).thenReturn(List.of(task));
    var consumed =
        ContinuationEscalationBudget.reconstruct(tasks, new ObjectMapper(), "12345678-run");
    assertThat(consumed.sameProviderRetriesByTask()).containsEntry("T1", 1);
    assertThat(consumed.providerSwitches()).isEqualTo(1);
    assertThat(consumed.stepUpsByTask()).isEmpty();
  }

  @Test
  void unavailableHistoryCannotRestoreAZeroSpendBudget() {
    AgentTaskRepository tasks = mock(AgentTaskRepository.class);
    when(tasks.findByRunId("12345678-run"))
        .thenThrow(new IllegalStateException("database unavailable"));
    assertThatThrownBy(
            () ->
                ContinuationEscalationBudget.reconstruct(tasks, new ObjectMapper(), "12345678-run"))
        .hasMessageContaining("BUDGET_EVIDENCE_UNAVAILABLE");
  }
}
