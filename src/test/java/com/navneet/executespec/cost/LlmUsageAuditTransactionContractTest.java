package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class LlmUsageAuditTransactionContractTest {

  @Test
  void reportAndCsvUseTheConfiguredDefaultIsolation() throws Exception {
    assertDefaultIsolation("report", LlmUsageAuditQuery.class);
    assertDefaultIsolation("csv", LlmUsageAuditQuery.class);
  }

  private static void assertDefaultIsolation(String method, Class<?>... parameters)
      throws Exception {
    Transactional annotation =
        LlmUsageAuditService.class.getMethod(method, parameters).getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.readOnly()).isTrue();
    assertThat(annotation.isolation()).isEqualTo(Isolation.DEFAULT);
  }
}
