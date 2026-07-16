package com.navneet.executespec.api.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.agentsession.SubagentBudgetExceededException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalExceptionAdviceTest {

  @Test
  void boundedSubagentBudgetIsAClientCorrectable422() {
    var response =
        new GlobalExceptionAdvice()
            .onSubagentBudgetExceeded(
                new SubagentBudgetExceededException(
                    "SUBAGENT_TREE_COST_BUDGET_EXHAUSTED", "tree cost budget exceeded"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody())
        .containsEntry("errorCode", "SUBAGENT_TREE_COST_BUDGET_EXHAUSTED")
        .containsEntry("error", "subagent-budget-exhausted")
        .containsEntry("status", 422);
  }
}
