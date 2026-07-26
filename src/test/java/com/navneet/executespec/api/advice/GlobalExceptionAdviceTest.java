package com.navneet.executespec.api.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.agentsession.SubagentBudgetExceededException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionAdviceTest {

  // RR-1119 — the status-derived error code uses the underscore convention CLI/core classifiers
  // recognize, matching onNoResource ("not_found") — not the old hyphenated "not-found".
  @Test
  void statusDerivedErrorCodeUsesUnderscoreNotHyphen() {
    var response =
        new GlobalExceptionAdvice()
            .onErrorResponse(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody())
        .containsEntry("error", "not_found")
        .containsEntry("status", 404)
        .containsEntry("message", "missing");
  }

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

  @Test
  void asyncSseTimeoutDoesNotFallThroughToJsonThrowableHandler() throws Exception {
    var method =
        GlobalExceptionAdvice.class.getMethod(
            "onAsyncRequestTimeout", AsyncRequestTimeoutException.class);

    assertThat(method.getReturnType()).isEqualTo(Void.TYPE);
    assertThat(method.getAnnotation(ExceptionHandler.class).value())
        .containsExactly(AsyncRequestTimeoutException.class);
    new GlobalExceptionAdvice().onAsyncRequestTimeout(new AsyncRequestTimeoutException());
  }
}
