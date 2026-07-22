package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ActivityAuditService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatSecurityAuditServiceTest {

  @Test
  void writesMinimalDecisionToHashChainedAuditWithoutArgumentsOrResults() {
    ActivityAuditService activity = mock(ActivityAuditService.class);
    ChatSecurityAuditService audit = new ChatSecurityAuditService(activity);
    AppUserDetails principal =
        new AppUserDetails(
            AppUser.builder()
                .id("user-1")
                .orgId("org-1")
                .email("user@example.com")
                .globalRole("USER")
                .status("ACTIVE")
                .build(),
            null);

    audit.record(
        "CHAT_TOOL_RESULT",
        "session-1",
        "project-1",
        principal,
        "search_code",
        ChatToolPolicy.Capability.PROJECT_READ,
        "OK");

    ArgumentCaptor<ActivityAudit> row = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(activity).record(row.capture());
    assertThat(row.getValue().getEventType()).isEqualTo("CHAT_TOOL_RESULT");
    assertThat(row.getValue().getEntityType()).isEqualTo("PROJECT_CHAT");
    assertThat(row.getValue().getActorId()).isEqualTo("user-1");
    assertThat(row.getValue().getMetadata())
        .contains("search_code", "PROJECT_READ", "OK")
        .doesNotContain("args", "result", "prompt", "secret");
  }

  /**
   * RR-375 — the host-org overload attributes the event to the project owner org, not the actor.
   */
  @Test
  void recordAttributesToHostOrgWhenProvided() {
    ActivityAuditService activity = mock(ActivityAuditService.class);
    ChatSecurityAuditService audit = new ChatSecurityAuditService(activity);
    AppUserDetails collaborator =
        new AppUserDetails(
            AppUser.builder()
                .id("user-collab")
                .orgId("collaborator-org")
                .email("collab@example.com")
                .globalRole("USER")
                .status("ACTIVE")
                .build(),
            null);

    audit.record(
        "CHAT_TOOL_ALLOWED",
        "project-1",
        "project-1",
        collaborator,
        "search_code",
        ChatToolPolicy.Capability.PROJECT_READ,
        "ALLOWED",
        "host-org");

    ArgumentCaptor<ActivityAudit> row = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(activity).record(row.capture());
    assertThat(row.getValue().getOrgId()).isEqualTo("host-org");
    assertThat(row.getValue().getActorId()).isEqualTo("user-collab");
  }

  /** RR-375 — a null host org falls back to the actor's home org (session-scoped callers). */
  @Test
  void recordFallsBackToActorOrgWhenHostOrgAbsent() {
    ActivityAuditService activity = mock(ActivityAuditService.class);
    ChatSecurityAuditService audit = new ChatSecurityAuditService(activity);
    AppUserDetails principal =
        new AppUserDetails(
            AppUser.builder()
                .id("user-1")
                .orgId("actor-org")
                .email("user@example.com")
                .globalRole("USER")
                .status("ACTIVE")
                .build(),
            null);

    audit.record(
        "CHAT_TOOL_ALLOWED",
        "session-1",
        "project-1",
        principal,
        "search_code",
        ChatToolPolicy.Capability.PROJECT_READ,
        "ALLOWED",
        null);

    ArgumentCaptor<ActivityAudit> row = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(activity).record(row.capture());
    assertThat(row.getValue().getOrgId()).isEqualTo("actor-org");
  }

  @Test
  void auditMetadataCannotBeBrokenByControlCharactersAndMissingIdentityFailsClosed()
      throws Exception {
    ActivityAuditService activity = mock(ActivityAuditService.class);
    ChatSecurityAuditService audit = new ChatSecurityAuditService(activity);

    audit.record(
        "CHAT_TOOL_DENIED", "", null, null, "search_code\"\\\nforged", null, "DENIED\r\nforged");

    ArgumentCaptor<ActivityAudit> row = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(activity).record(row.capture());
    ActivityAudit value = row.getValue();
    assertThat(value.getEntityType()).isEqualTo("CHAT_SESSION");
    assertThat(value.getEntityId()).isEqualTo("unknown");
    assertThat(value.getActorId()).isNull();
    assertThat(value.getOrgId()).isNull();
    assertThat(value.getMetadata()).doesNotContain("\n", "\r", "\\");
    assertThat(new ObjectMapper().readTree(value.getMetadata()).path("capability").asText())
        .isEqualTo("UNKNOWN");
  }
}
