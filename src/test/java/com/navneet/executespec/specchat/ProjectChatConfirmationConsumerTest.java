package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProjectChatConfirmationConsumerTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");

  @Test
  void consumesMatchingScopeExactlyOnceAndPersistsBeforeReturning() {
    var repository = mock(ProjectChatConfirmationRepository.class);
    var consumer =
        new ProjectChatConfirmationConsumer(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    UUID id = UUID.randomUUID();
    var row = pending(id, NOW.plusSeconds(60));
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(row));

    var attempt = consumer.consume(id, "project-1", "org-1", "user-1", "token-hash", "scope-hash");

    assertThat(attempt.outcome()).isEqualTo(ProjectChatConfirmationConsumer.Outcome.CONSUMED);
    assertThat(row.getStatus()).isEqualTo(ProjectChatConfirmation.Status.CONSUMED);
    assertThat(row.getConsumedAt()).isEqualTo(NOW);
    verify(repository).save(row);

    var replay = consumer.consume(id, "project-1", "org-1", "user-1", "token-hash", "scope-hash");
    assertThat(replay.outcome()).isEqualTo(ProjectChatConfirmationConsumer.Outcome.REPLAY);
  }

  @Test
  void expiresUnderLockAndNeverConsumes() {
    var repository = mock(ProjectChatConfirmationRepository.class);
    var consumer =
        new ProjectChatConfirmationConsumer(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    UUID id = UUID.randomUUID();
    var row = pending(id, NOW);
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(row));

    var attempt = consumer.consume(id, "project-1", "org-1", "user-1", "token-hash", "scope-hash");

    assertThat(attempt.outcome()).isEqualTo(ProjectChatConfirmationConsumer.Outcome.EXPIRED);
    assertThat(row.getStatus()).isEqualTo(ProjectChatConfirmation.Status.EXPIRED);
    assertThat(row.getConsumedAt()).isNull();
    verify(repository).save(row);
  }

  @Test
  void rejectsActorTenantScopeAndTokenSubstitutionWithoutChangingState() {
    UUID id = UUID.randomUUID();
    var row = pending(id, NOW.plusSeconds(60));
    var repository = mock(ProjectChatConfirmationRepository.class);
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(row));
    var consumer =
        new ProjectChatConfirmationConsumer(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(
            consumer.consume(id, "other", "org-1", "user-1", "token-hash", "scope-hash").outcome())
        .isEqualTo(ProjectChatConfirmationConsumer.Outcome.TENANT_MISMATCH);
    assertThat(
            consumer
                .consume(id, "project-1", "org-1", "other", "token-hash", "scope-hash")
                .outcome())
        .isEqualTo(ProjectChatConfirmationConsumer.Outcome.ACTOR_MISMATCH);
    assertThat(
            consumer.consume(id, "project-1", "org-1", "user-1", "token-hash", "other").outcome())
        .isEqualTo(ProjectChatConfirmationConsumer.Outcome.SCOPE_MISMATCH);
    assertThat(
            consumer.consume(id, "project-1", "org-1", "user-1", "other", "scope-hash").outcome())
        .isEqualTo(ProjectChatConfirmationConsumer.Outcome.TOKEN_MISMATCH);
    assertThat(row.getStatus()).isEqualTo(ProjectChatConfirmation.Status.PENDING);
    assertThat(row.getConsumedAt()).isNull();
  }

  private static ProjectChatConfirmation pending(UUID id, Instant expiresAt) {
    return ProjectChatConfirmation.builder()
        .id(id)
        .projectId("project-1")
        .orgId("org-1")
        .actorUserId("user-1")
        .action("GENERATE_PROJECT_DOC")
        .capability("SPEND_AI")
        .provider("deepseek")
        .model("deepseek-v4")
        .targetJson("{\"docType\":\"SETUP\"}")
        .targetHash("target-hash")
        .scopeHash("scope-hash")
        .tokenHash("token-hash")
        .expiresAt(expiresAt)
        .build();
  }
}
