package com.navneet.executespec.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.service.ActivityAuditService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for {@link ActivityEventEmitter} — focus on the dual-write contract (ACTIVITY + AUDIT
 * for dual-surface events; only the matching table for single-surface events) and the non-blocking
 * failure mode (downstream errors logged + swallowed, never rethrown).
 */
@ExtendWith(MockitoExtension.class)
class ActivityEventEmitterTest {

  @Mock ActivityFeedEntryRepository feedRepo;
  @Mock ActivityAuditService auditService;
  @Mock ProjectRepository projectRepository;

  ActivityEventEmitter emitter;

  @BeforeEach
  void setUp() {
    emitter =
        new ActivityEventEmitter(feedRepo, auditService, new ObjectMapper(), projectRepository);
  }

  @Test
  void activityOnlyEvent_writesOnlyToFeedTable() {
    emitter.emit(
        UUID.randomUUID(),
        ActivityEventType.RUN_SUBMITTED,
        UUID.randomUUID(),
        "RUN",
        "run-123",
        "Submitted run X",
        Map.of("requirementChars", 200));

    verify(feedRepo, times(1)).save(any(ActivityFeedEntry.class));
    verify(auditService, never()).record(any());
  }

  @Test
  void auditOnlyEvent_writesOnlyToAuditTable() {
    emitter.emit(
        UUID.randomUUID(),
        ActivityEventType.BILLING_CHANGE,
        UUID.randomUUID(),
        "ORG",
        "org-123",
        "Plan changed",
        Map.of("from", "FREE", "to", "PRO"));

    verify(feedRepo, never()).save(any());
    verify(auditService, times(1)).record(any(ActivityAudit.class));
  }

  @Test
  void dualSurfaceEvent_writesToBothTables() {
    // MEMBER_ROLE_CHANGED is dual-surface per plan §3.3 — RBAC depends on it.
    emitter.emit(
        UUID.randomUUID(),
        ActivityEventType.MEMBER_ROLE_CHANGED,
        UUID.randomUUID(),
        "MEMBER",
        "user-456",
        "Role: PROJECT_DEVELOPER → PROJECT_ADMIN",
        Map.of("oldRole", "PROJECT_DEVELOPER", "newRole", "PROJECT_ADMIN"));

    verify(feedRepo, times(1)).save(any(ActivityFeedEntry.class));
    verify(auditService, times(1)).record(any(ActivityAudit.class));
  }

  @Test
  void projectAuditCarriesTheOwningOrganizationForEnterpriseQueries() {
    UUID projectId = UUID.randomUUID();
    when(projectRepository.findById(projectId.toString()))
        .thenReturn(
            java.util.Optional.of(
                Project.builder()
                    .id(projectId.toString())
                    .orgId("org-1")
                    .name("Project")
                    .createdBy("user-1")
                    .build()));
    ArgumentCaptor<ActivityAudit> audit = ArgumentCaptor.forClass(ActivityAudit.class);

    emitter.emit(
        projectId,
        ActivityEventType.MEMBER_ROLE_CHANGED,
        UUID.randomUUID(),
        "MEMBER",
        "user-456",
        "Role changed",
        null);

    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getOrgId()).isEqualTo("org-1");
  }

  @Test
  void nullActorMakesActorTypeSystem() {
    ArgumentCaptor<ActivityFeedEntry> captor = ArgumentCaptor.forClass(ActivityFeedEntry.class);
    emitter.emit(
        UUID.randomUUID(),
        ActivityEventType.CODEMAP_REGENERATED,
        null, // background job → no user
        "REPO",
        "repo-789",
        "Codemap regenerated post-merge",
        null);

    verify(feedRepo).save(captor.capture());
    assertThat(captor.getValue().getActorType()).isEqualTo("SYSTEM");
    assertThat(captor.getValue().getActorUserId()).isNull();
  }

  @Test
  void presentActorMakesActorTypeUser() {
    UUID actor = UUID.randomUUID();
    ArgumentCaptor<ActivityFeedEntry> captor = ArgumentCaptor.forClass(ActivityFeedEntry.class);
    emitter.emit(
        UUID.randomUUID(),
        ActivityEventType.RUN_PLAN_APPROVED,
        actor,
        "RUN",
        "run-1",
        "Plan approved",
        null);

    verify(feedRepo).save(captor.capture());
    assertThat(captor.getValue().getActorType()).isEqualTo("USER");
    assertThat(captor.getValue().getActorUserId()).isEqualTo(actor);
  }

  @Test
  void schemaVersion_isCopiedFromEnumDefinition() {
    ArgumentCaptor<ActivityFeedEntry> captor = ArgumentCaptor.forClass(ActivityFeedEntry.class);
    emitter.emit(
        UUID.randomUUID(),
        ActivityEventType.RUN_SUBMITTED,
        UUID.randomUUID(),
        "RUN",
        "run-1",
        "summary",
        null);

    verify(feedRepo).save(captor.capture());
    assertThat(captor.getValue().getSchemaVersion())
        .isEqualTo(ActivityEventType.RUN_SUBMITTED.schemaVersion());
  }

  @Test
  void payloadSerializedAsJson_orNullWhenEmpty() {
    ArgumentCaptor<ActivityFeedEntry> captor = ArgumentCaptor.forClass(ActivityFeedEntry.class);

    emitter.emit(
        UUID.randomUUID(),
        ActivityEventType.RUN_SUBMITTED,
        UUID.randomUUID(),
        "RUN",
        "r1",
        "s1",
        Map.of("a", 1, "b", "two"));
    verify(feedRepo).save(captor.capture());
    assertThat(captor.getValue().getPayload()).contains("\"a\":1").contains("\"b\":\"two\"");

    reset(feedRepo);
    emitter.emit(
        UUID.randomUUID(),
        ActivityEventType.RUN_SUBMITTED,
        UUID.randomUUID(),
        "RUN",
        "r2",
        "s2",
        Map.of());
    verify(feedRepo).save(captor.capture());
    assertThat(captor.getValue().getPayload()).isNull();
  }

  @Test
  void missingRequiredFieldsAreSkippedSilently() {
    emitter.emit(null, ActivityEventType.RUN_SUBMITTED, UUID.randomUUID(), "T", "t", "s", null);
    emitter.emit(UUID.randomUUID(), null, UUID.randomUUID(), "T", "t", "s", null);
    emitter.emit(
        UUID.randomUUID(),
        ActivityEventType.RUN_SUBMITTED,
        UUID.randomUUID(),
        "T",
        "t",
        null,
        null);

    verifyNoInteractions(feedRepo, auditService);
  }

  @Test
  void persistFailureIsSwallowed_neverRethrown() {
    String secret = "jdbc password=customer-secret";
    when(feedRepo.save(any())).thenThrow(new RuntimeException(secret));
    Logger logger = (Logger) LoggerFactory.getLogger(ActivityEventEmitter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try {
      // Must not throw — non-blocking contract per plan §1.9.
      emitter.emit(
          UUID.randomUUID(),
          ActivityEventType.RUN_SUBMITTED,
          UUID.randomUUID(),
          "RUN",
          "run-1",
          "submitted",
          null);

      verify(feedRepo).save(any());
      assertThat(appender.list)
          .extracting(ILoggingEvent::getFormattedMessage)
          .allSatisfy(message -> assertThat(message).doesNotContain(secret));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
