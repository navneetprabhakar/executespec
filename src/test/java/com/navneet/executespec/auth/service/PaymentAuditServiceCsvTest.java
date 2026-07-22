package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import com.navneet.executespec.auth.entity.PaymentAudit;
import com.navneet.executespec.auth.repository.PaymentAuditRepository;

import org.junit.jupiter.api.Test;

/**
 * RR-242 — the payment-audit CSV export is downloaded by operators straight into Excel/Sheets, so
 * every textual cell must be neutralized against formula injection. A cell whose first
 * non-whitespace character is one of {@code = + - @} is prefixed with a single quote so it renders
 * as text instead of executing.
 */
class PaymentAuditServiceCsvTest {

  private final PaymentAuditService service =
      new PaymentAuditService(mock(PaymentAuditRepository.class));

  @Test
  void neutralizesLeadingFormulaMarkersInTextualCells() {
    PaymentAudit row =
        PaymentAudit.builder()
            .id("id-1")
            .entityType("USER")
            .entityId("u1")
            .eventType("TIER_CHANGE")
            .performedBy("=cmd|'/c calc'!A1")
            .notes("@SUM(A1:A9)")
            .build();

    String csv = service.exportCsv(List.of(row));

    // The dangerous cells are quoted AND prefixed with a single quote, so no cell begins with a
    // bare formula marker after the opening quote.
    assertThat(csv).contains("\"'=cmd");
    assertThat(csv).contains("\"'@SUM(A1:A9)\"");
    // The whole-file must not contain an unquoted cell that starts with a formula marker.
    assertThat(csv).doesNotContain(",=cmd");
    assertThat(csv).doesNotContain(",@SUM");
  }

  @Test
  void neutralizesFormulaAfterLeadingWhitespace() {
    PaymentAudit row = PaymentAudit.builder().id("id-1").eventType("X").notes("   -2+3").build();

    String csv = service.exportCsv(List.of(row));

    // Leading whitespace does not hide the formula marker; the cell is neutralized.
    assertThat(csv).contains("\"'   -2+3\"");
  }
}
