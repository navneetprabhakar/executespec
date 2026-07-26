package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RunControllerExecutionModePolicyTest {

  @Test
  void rejectsNewBatchApprovalWithExplicitRetirementStatus() {
    assertThatThrownBy(
            () ->
                com.navneet.executespec.integration.PlanApprovalService.rejectRetiredBatchApproval(
                    true, "batch"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              org.assertj.core.api.Assertions.assertThat(error.getStatusCode())
                  .isEqualTo(HttpStatus.GONE);
              org.assertj.core.api.Assertions.assertThat(error.getReason())
                  .contains("BATCH_EXECUTION_RETIRED");
            });
  }

  @Test
  void allowsRealtimeAndRejectedApprovalsToPreserveCompatibility() {
    com.navneet.executespec.integration.PlanApprovalService.rejectRetiredBatchApproval(
        true, "REALTIME");
    com.navneet.executespec.integration.PlanApprovalService.rejectRetiredBatchApproval(
        false, "BATCH");
    com.navneet.executespec.integration.PlanApprovalService.rejectRetiredBatchApproval(false, null);
  }
}
