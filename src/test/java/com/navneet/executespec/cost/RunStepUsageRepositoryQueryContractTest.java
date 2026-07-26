package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Pins the known/unknown-cost partition in the repository queries. The router deliberately writes a
 * negative sentinel after a successful response whose price projection failed; silently removing
 * either predicate would let that unknown spend lower run totals or execution budgets.
 */
class RunStepUsageRepositoryQueryContractTest {

  @Test
  void runKnownCostExcludesNegativeSentinelAndUnknownCountIncludesIt() throws Exception {
    assertThat(query("sumKnownCostByRunId", String.class))
        .contains("u.costUsd >= 0.0")
        .contains("u.attemptStatus")
        .contains("SUCCESS");
    assertThat(query("countUnknownCostAttemptsByRunId", String.class))
        .contains("u.costUsd < 0.0")
        .contains("u.attemptStatus")
        .contains("SUCCESS");
  }

  @Test
  void contextBudgetSeparatesKnownSpendFromUnknownSuccessfulCalls() throws Exception {
    assertThat(query("sumSuccessfulCostByContextId", String.class))
        .contains("u.costUsd >= 0.0")
        .contains("u.llmExecutionContextId = :contextId");
    assertThat(query("countUnknownSuccessfulCostAttemptsByContextId", String.class))
        .contains("u.costUsd < 0.0")
        .contains("u.attemptStatus = 'SUCCESS'");
  }

  private static String query(String name, Class<?>... parameterTypes) throws Exception {
    Method method = RunStepUsageRepository.class.getMethod(name, parameterTypes);
    return method.getAnnotation(Query.class).value();
  }
}
