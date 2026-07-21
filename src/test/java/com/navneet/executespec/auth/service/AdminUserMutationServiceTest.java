package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/** RR-009 / RR-010 — validated, invariant-protected, structured-audit user mutations. */
class AdminUserMutationServiceTest {

  private AppUserRepository userRepo;
  private ActivityAuditService auditService;
  private PaymentAuditService paymentAuditService;
  private AdminUserMutationService service;

  @BeforeEach
  void setUp() {
    userRepo = org.mockito.Mockito.mock(AppUserRepository.class);
    auditService = org.mockito.Mockito.mock(ActivityAuditService.class);
    paymentAuditService = org.mockito.Mockito.mock(PaymentAuditService.class);
    service =
        new AdminUserMutationService(
            userRepo, auditService, paymentAuditService, new ObjectMapper());
  }

  private AppUser user(String id, String status, String tier, String role) {
    AppUser u = new AppUser();
    u.setId(id);
    u.setStatus(status);
    u.setTier(tier);
    u.setGlobalRole(role);
    return u;
  }

  // RR-010: an out-of-domain status is a 400, not a persisted invalid state.
  @Test
  void updateStatus_rejectsInvalidValue() {
    assertThatThrownBy(() -> service.updateStatus("admin", "u1", "BOGUS"))
        .isInstanceOf(ResponseStatusException.class);
    verify(userRepo, never()).save(any());
  }

  // RR-010: self-suspension is refused (lockout guard).
  @Test
  void updateStatus_rejectsSelfSuspension() {
    when(userRepo.findByIdForUpdate("admin"))
        .thenReturn(Optional.of(user("admin", "ACTIVE", "ENTERPRISE", "SUPER_ADMIN")));
    assertThatThrownBy(() -> service.updateStatus("admin", "admin", "SUSPENDED"))
        .isInstanceOf(ResponseStatusException.class);
    verify(userRepo, never()).save(any());
  }

  // RR-010: cannot suspend the last active SUPER_ADMIN.
  @Test
  void updateStatus_rejectsSuspendingLastSuperAdmin() {
    when(userRepo.findByIdForUpdate("target"))
        .thenReturn(Optional.of(user("target", "ACTIVE", "ENTERPRISE", "SUPER_ADMIN")));
    when(userRepo.countActiveSuperAdmins()).thenReturn(1L);
    assertThatThrownBy(() -> service.updateStatus("admin", "target", "SUSPENDED"))
        .isInstanceOf(ResponseStatusException.class);
    verify(userRepo, never()).save(any());
  }

  // RR-009/RR-010: a valid status change persists AND writes a structured audit row in the same
  // transaction (well-formed JSON, not string concatenation).
  @Test
  void updateStatus_valid_persistsAndAudits() {
    when(userRepo.findByIdForUpdate("target"))
        .thenReturn(Optional.of(user("target", "ACTIVE", "PRO", "USER")));
    AdminUserMutationService.StatusChange result =
        service.updateStatus("admin", "target", "suspended");
    assertThat(result.newStatus()).isEqualTo("SUSPENDED");
    assertThat(result.newlySuspended()).isTrue();
    verify(userRepo).save(any(AppUser.class));

    ArgumentCaptor<ActivityAudit> cap = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(auditService).record(cap.capture());
    assertThat(cap.getValue().getEventType()).isEqualTo("USER_STATUS_CHANGED");
    assertThat(cap.getValue().getNewValue()).isEqualTo("{\"status\":\"SUSPENDED\"}");
  }

  // RR-010: cannot demote the last active SUPER_ADMIN.
  @Test
  void updateRole_rejectsDemotingLastSuperAdmin() {
    when(userRepo.findByIdForUpdate("target"))
        .thenReturn(Optional.of(user("target", "ACTIVE", "ENTERPRISE", "SUPER_ADMIN")));
    when(userRepo.countActiveSuperAdmins()).thenReturn(1L);
    assertThatThrownBy(() -> service.updateRole("admin", "target", "USER"))
        .isInstanceOf(ResponseStatusException.class);
    verify(userRepo, never()).save(any());
  }

  // RR-010: an admin cannot remove their own SUPER_ADMIN role.
  @Test
  void updateRole_rejectsSelfDemotion() {
    when(userRepo.findByIdForUpdate("admin"))
        .thenReturn(Optional.of(user("admin", "ACTIVE", "ENTERPRISE", "SUPER_ADMIN")));
    when(userRepo.countActiveSuperAdmins()).thenReturn(3L);
    assertThatThrownBy(() -> service.updateRole("admin", "admin", "USER"))
        .isInstanceOf(ResponseStatusException.class);
    verify(userRepo, never()).save(any());
  }

  // RR-010: an out-of-domain tier is a 400.
  @Test
  void updateTier_rejectsInvalidValue() {
    assertThatThrownBy(() -> service.updateTier("admin", "u1", "GOLD"))
        .isInstanceOf(ResponseStatusException.class);
    verify(userRepo, never()).save(any());
  }

  @Test
  void updateTier_valid_persistsAndAudits() {
    when(userRepo.findByIdForUpdate("target"))
        .thenReturn(Optional.of(user("target", "ACTIVE", "FREE", "USER")));
    AdminUserMutationService.TierChange result = service.updateTier("admin", "target", "pro");
    assertThat(result.newTier()).isEqualTo("PRO");
    assertThat(result.changed()).isTrue();
    verify(userRepo).save(any(AppUser.class));
    verify(paymentAuditService)
        .record("USER", "target", "PLAN_UPGRADED", "FREE", "PRO", "admin", null);
    verify(auditService).record(any(ActivityAudit.class));
  }
}
