package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.billing.entity.TopupPackage;
import com.navneet.executespec.billing.repository.TopupPackageRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/** RR-011 — validated money/count and mandatory in-transaction audit for top-up packages. */
class TopupPackageAdminServiceTest {

  private TopupPackageRepository packageRepo;
  private ActivityAuditService auditService;
  private TopupPackageAdminService service;

  @BeforeEach
  void setUp() {
    packageRepo = org.mockito.Mockito.mock(TopupPackageRepository.class);
    auditService = org.mockito.Mockito.mock(ActivityAuditService.class);
    service = new TopupPackageAdminService(packageRepo, auditService, new ObjectMapper());
    when(packageRepo.save(any(TopupPackage.class)))
        .thenAnswer(inv -> inv.getArgument(0, TopupPackage.class));
  }

  private Map<String, Object> validBody() {
    Map<String, Object> b = new HashMap<>();
    b.put("name", "10 runs");
    b.put("runCount", 10);
    b.put("amountInr", 50000L);
    b.put("amountUsd", 600L);
    return b;
  }

  // RR-011: a non-positive run count is rejected with a 400 and nothing is written.
  @Test
  void create_rejectsZeroRunCount() {
    Map<String, Object> b = validBody();
    b.put("runCount", 0);
    assertThatThrownBy(() -> service.create("admin", b))
        .isInstanceOf(ResponseStatusException.class);
    verify(packageRepo, never()).save(any());
    verify(auditService, never()).record(any());
  }

  // RR-011: a negative price is rejected.
  @Test
  void create_rejectsNegativePrice() {
    Map<String, Object> b = validBody();
    b.put("amountInr", -1L);
    assertThatThrownBy(() -> service.create("admin", b))
        .isInstanceOf(ResponseStatusException.class);
    verify(packageRepo, never()).save(any());
  }

  // RR-011: a blank name is rejected.
  @Test
  void create_rejectsBlankName() {
    Map<String, Object> b = validBody();
    b.put("name", "   ");
    assertThatThrownBy(() -> service.create("admin", b))
        .isInstanceOf(ResponseStatusException.class);
    verify(packageRepo, never()).save(any());
  }

  // RR-011: a valid create persists AND records the audit event (same transaction).
  @Test
  void create_valid_persistsAndAudits() {
    TopupPackage pkg = service.create("admin", validBody());
    assertThat(pkg.getRunCount()).isEqualTo(10);
    assertThat(pkg.isActive()).isTrue();
    verify(packageRepo).save(any(TopupPackage.class));

    ArgumentCaptor<ActivityAudit> cap = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(auditService).record(cap.capture());
    assertThat(cap.getValue().getEventType()).isEqualTo("TOPUP_PACKAGE_CREATED");
    // RR-009: structured JSON, not concatenation.
    assertThat(cap.getValue().getNewValue()).contains("\"runCount\":10");
  }

  // RR-011: update also validates.
  @Test
  void update_rejectsNegativeRunCount() {
    java.util.UUID id = java.util.UUID.randomUUID();
    when(packageRepo.findById(id))
        .thenReturn(java.util.Optional.of(TopupPackage.builder().id(id).name("x").build()));
    Map<String, Object> b = new HashMap<>();
    b.put("runCount", -5);
    assertThatThrownBy(() -> service.update("admin", id, b))
        .isInstanceOf(ResponseStatusException.class);
    verify(packageRepo, never()).save(any());
  }
}
