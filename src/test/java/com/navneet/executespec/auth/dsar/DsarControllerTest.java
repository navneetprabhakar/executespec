package com.navneet.executespec.auth.dsar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.PaymentAudit;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.PaymentAuditRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.notification.NotificationRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * RR-229 / RR-230 — the self-service DSAR export must disclose ONLY the requesting subject's own
 * personal data, and must never silently truncate a section without saying so.
 */
class DsarControllerTest {

  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final NotificationRepository notifRepo = mock(NotificationRepository.class);
  private final PaymentAuditRepository paymentRepo = mock(PaymentAuditRepository.class);
  private final DsarController controller =
      new DsarController(userRepo, runRepo, notifRepo, paymentRepo);

  @Test
  @SuppressWarnings("unchecked")
  void selfExportReturnsOnlyTheSubjectsOwnPaymentsNotTheWholeOrg() {
    AppUser subject = new AppUser();
    subject.setId("subject-1");
    subject.setEmail("subject@example.com");
    subject.setOrgId("org-1"); // subject shares an org with other paying members
    when(userRepo.findById("subject-1")).thenReturn(Optional.of(subject));

    PaymentAudit ownRow =
        PaymentAudit.builder().id("p-own").userId("subject-1").orgId("org-1").build();
    // The repository is queried BY SUBJECT USER ID only. If the controller ever reached for the
    // org-wide rows again (the RR-229 defect), this stub would not be the one consulted.
    when(paymentRepo.findByUserIdOrderByCreatedAtDesc(eq("subject-1"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(ownRow)));
    when(runRepo.findByCreatedByUserIdOrderByStartedAtDesc(eq("subject-1"), any(Pageable.class)))
        .thenReturn(Page.empty());
    when(notifRepo.findByUserIdOrderByCreatedAtDesc(eq("subject-1"), any(Pageable.class)))
        .thenReturn(Page.empty());

    Map<String, Object> export = controller.exportSelf(new AppUserDetails(subject, null)).getBody();

    List<Map<String, Object>> payments = (List<Map<String, Object>>) export.get("payments");
    assertThat(payments).hasSize(1);
    assertThat(payments.get(0)).containsEntry("id", "p-own");
  }

  @Test
  @SuppressWarnings("unchecked")
  void everySectionReportsWhetherItWasTruncated() {
    AppUser subject = new AppUser();
    subject.setId("subject-1");
    subject.setEmail("subject@example.com");
    when(userRepo.findById("subject-1")).thenReturn(Optional.of(subject));

    // Payments: total (5000) exceeds the returned page (1 row) => truncated.
    PaymentAudit row = PaymentAudit.builder().id("p1").userId("subject-1").build();
    Page<PaymentAudit> truncatedPayments =
        new PageImpl<>(List.of(row), PageRequest.of(0, DsarController.MAX_ROWS), 5_000L);
    when(paymentRepo.findByUserIdOrderByCreatedAtDesc(eq("subject-1"), any(Pageable.class)))
        .thenReturn(truncatedPayments);
    when(runRepo.findByCreatedByUserIdOrderByStartedAtDesc(eq("subject-1"), any(Pageable.class)))
        .thenReturn(Page.empty());
    when(notifRepo.findByUserIdOrderByCreatedAtDesc(eq("subject-1"), any(Pageable.class)))
        .thenReturn(Page.empty());

    Map<String, Object> export = controller.exportSelf(new AppUserDetails(subject, null)).getBody();

    Map<String, Object> truncation = (Map<String, Object>) export.get("truncation");
    assertThat(truncation).containsEntry("maxRowsPerSection", DsarController.MAX_ROWS);
    Map<String, Object> paymentsTrunc = (Map<String, Object>) truncation.get("payments");
    assertThat(paymentsTrunc)
        .containsEntry("returned", 1)
        .containsEntry("total", 5_000L)
        .containsEntry("truncated", true);
    Map<String, Object> notifsTrunc = (Map<String, Object>) truncation.get("notifications");
    assertThat(notifsTrunc).containsEntry("truncated", false);
  }
}
