package com.navneet.executespec.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ActivityFeedControllerTest {

  @Mock ActivityFeedEntryRepository feedRepo;
  // GA-readiness P4.1-followup — test fixture mocks the new tenant guard. The
  // default no-op `assertCanRead` (Mockito returns null for non-stubbed calls)
  // is the correct behavior for these unit tests, which are scoped to the
  // controller's filtering logic, not the guard itself. Cross-org 403 paths
  // are exercised by ProjectAccessGuardTest separately.
  @Mock com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;
  @InjectMocks ActivityFeedController controller;

  UUID projectUuid;
  String projectId;

  @BeforeEach
  void setUp() {
    projectUuid = UUID.randomUUID();
    projectId = projectUuid.toString();
  }

  @Test
  void rejectsNonUuidProjectId() {
    assertThatThrownBy(
            () -> controller.list("not-a-uuid", 30, null, null, null, null, false, 100, fakeAuth()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rejectsUnauthenticatedCallers() {
    assertThatThrownBy(
            () -> controller.list(projectId, 30, null, null, null, null, false, 100, null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void filtersAuditOnlyEvents_evenWhenNoExplicitFilter() {
    when(feedRepo.findRecentForProject(any(), any(), any()))
        .thenReturn(
            List.of(
                entry(ActivityEventType.RUN_SUBMITTED),
                entry(ActivityEventType.BILLING_CHANGE), // AUDIT_ONLY → must be filtered out
                entry(ActivityEventType.RUN_COMPLETED)));

    ResponseEntity<ActivityFeedController.ActivityPage> res =
        controller.list(projectId, 30, null, null, null, null, false, 100, fakeAuth());

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody().entries()).hasSize(2);
    assertThat(res.getBody().entries()).noneMatch(e -> "BILLING_CHANGE".equals(e.eventType()));
  }

  @Test
  void filtersAdminToggleEvents_unlessIncludeAdminEventsTrue() {
    when(feedRepo.findRecentForProject(any(), any(), any()))
        .thenReturn(
            List.of(
                entry(ActivityEventType.RUN_SUBMITTED),
                entry(ActivityEventType.AUTO_DETECTION_RAN), // ADMIN_TOGGLE
                entry(ActivityEventType.CODEMAP_REGENERATED))); // ADMIN_TOGGLE

    ResponseEntity<ActivityFeedController.ActivityPage> hidden =
        controller.list(projectId, 30, null, null, null, null, false, 100, fakeAuth());
    assertThat(hidden.getBody().entries()).hasSize(1);

    ResponseEntity<ActivityFeedController.ActivityPage> included =
        controller.list(projectId, 30, null, null, null, null, true, 100, fakeAuth());
    assertThat(included.getBody().entries()).hasSize(3);
  }

  @Test
  void filtersByEventTypeWhenProvided() {
    when(feedRepo.findRecentForProject(any(), any(), any()))
        .thenReturn(
            List.of(
                entry(ActivityEventType.RUN_SUBMITTED),
                entry(ActivityEventType.PR_OPENED),
                entry(ActivityEventType.PR_MERGED)));

    ResponseEntity<ActivityFeedController.ActivityPage> res =
        controller.list(
            projectId,
            30,
            List.of("PR_OPENED", "PR_MERGED"),
            null,
            null,
            null,
            false,
            100,
            fakeAuth());

    assertThat(res.getBody().entries()).hasSize(2);
    assertThat(res.getBody().entries())
        .extracting(ActivityFeedController.ActivityEntry::eventType)
        .containsExactlyInAnyOrder("PR_OPENED", "PR_MERGED");
  }

  @Test
  void filtersByActorUserId() {
    UUID actor = UUID.randomUUID();
    ActivityFeedEntry e1 = entry(ActivityEventType.RUN_SUBMITTED);
    e1.setActorUserId(actor);
    ActivityFeedEntry e2 = entry(ActivityEventType.RUN_SUBMITTED);
    e2.setActorUserId(UUID.randomUUID());

    when(feedRepo.findRecentForProject(any(), any(), any())).thenReturn(List.of(e1, e2));
    ResponseEntity<ActivityFeedController.ActivityPage> res =
        controller.list(projectId, 30, null, actor.toString(), null, null, false, 100, fakeAuth());
    assertThat(res.getBody().entries()).hasSize(1);
    assertThat(res.getBody().entries().get(0).actorUserId()).isEqualTo(actor.toString());
  }

  @Test
  void hasMoreFlag_setWhenLimitReached() {
    // Generate 101 HIGH_SIGNAL events; with limit=100 the controller stops
    // after 100 → hasMore=true
    List<ActivityFeedEntry> many = new java.util.ArrayList<>();
    for (int i = 0; i < 101; i++) many.add(entry(ActivityEventType.RUN_SUBMITTED));
    when(feedRepo.findRecentForProject(any(), any(), any())).thenReturn(many);

    ResponseEntity<ActivityFeedController.ActivityPage> res =
        controller.list(projectId, 30, null, null, null, null, false, 100, fakeAuth());
    assertThat(res.getBody().entries()).hasSize(100);
    assertThat(res.getBody().hasMore()).isTrue();
  }

  @Test
  void clampsLimitToValidRange() {
    when(feedRepo.findRecentForProject(any(), any(), any()))
        .thenReturn(List.of(entry(ActivityEventType.RUN_SUBMITTED)));
    // limit=0 → clamped to 1; limit=10000 → clamped to 500. Both should
    // succeed without throwing.
    controller.list(projectId, 30, null, null, null, null, false, 0, fakeAuth());
    controller.list(projectId, 30, null, null, null, null, false, 10000, fakeAuth());
  }

  private ActivityFeedEntry entry(ActivityEventType type) {
    return ActivityFeedEntry.builder()
        .id(UUID.randomUUID())
        .projectId(projectUuid)
        .occurredAt(Instant.now())
        .actorType("USER")
        .eventType(type.name())
        .summary("test event")
        .schemaVersion(1)
        .build();
  }

  private UsernamePasswordAuthenticationToken fakeAuth() {
    return new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), "n/a");
  }
}
