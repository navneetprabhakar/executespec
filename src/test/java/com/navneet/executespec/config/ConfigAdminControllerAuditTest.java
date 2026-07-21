package com.navneet.executespec.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ActivityAuditService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ConfigAdminControllerAuditTest {

  @Test
  void successfulDurableUpdateWritesSecretFreeActorAudit() {
    GcsRuntimeConfig runtimeConfig = mock(GcsRuntimeConfig.class);
    ActivityAuditService audit = mock(ActivityAuditService.class);
    ConfigAdminController controller =
        new ConfigAdminController(runtimeConfig, mock(ConfigRefreshService.class), audit);
    AppUserDetails user = user();

    controller.putConfig("agent.concurrency.max-runs", Map.of("value", "8"), user);

    verify(runtimeConfig).put("agent.concurrency.max-runs", "8");
    ArgumentCaptor<ActivityAudit> event = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(audit).record(event.capture());
    assertThat(event.getValue().getEventType()).isEqualTo("RUNTIME_CONFIG_UPDATED");
    assertThat(event.getValue().getActorId()).isEqualTo("super-1");
    assertThat(event.getValue().getEntityId()).isEqualTo("agent.concurrency.max-runs");
    assertThat(event.getValue().getNewValue()).isEqualTo("{\"present\":true}");
  }

  @Test
  void failedPersistenceReturnsUnavailableAndDoesNotWriteAudit() {
    GcsRuntimeConfig runtimeConfig = mock(GcsRuntimeConfig.class);
    ActivityAuditService audit = mock(ActivityAuditService.class);
    doThrow(new IllegalStateException("gcs down"))
        .when(runtimeConfig)
        .put("agent.concurrency.max-runs", "8");
    ConfigAdminController controller =
        new ConfigAdminController(runtimeConfig, mock(ConfigRefreshService.class), audit);

    assertThatThrownBy(
            () -> controller.putConfig("agent.concurrency.max-runs", Map.of("value", "8"), user()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode().value())
                    .isEqualTo(503));
    verify(audit, never()).record(org.mockito.ArgumentMatchers.any());
  }

  private static AppUserDetails user() {
    return new AppUserDetails(
        AppUser.builder()
            .id("super-1")
            .email("admin@example.com")
            .firstName("Admin")
            .globalRole("SUPER_ADMIN")
            .status("ACTIVE")
            .build(),
        null);
  }
}
