package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.specchat.ProjectChatConfirmation;
import com.navneet.executespec.specchat.ProjectChatConfirmationConsumer;
import com.navneet.executespec.specchat.ProjectChatConfirmationRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Proves one-shot confirmation consumption under a real PostgreSQL row lock. */
class ProjectChatConfirmationConcurrencyIntegrationTest extends PostgresIntegrationBase {

  @Autowired private ProjectChatConfirmationRepository confirmations;
  @Autowired private ProjectChatConfirmationConsumer consumer;
  @Autowired private JdbcTemplate jdbc;

  private final String suffix = UUID.randomUUID().toString();
  private final String orgId = "chat-org-" + suffix;
  private final String userId = "chat-user-" + suffix;
  private final String projectId = "chat-project-" + suffix;
  private UUID confirmationId;

  @AfterEach
  void cleanup() {
    if (confirmationId != null) confirmations.deleteById(confirmationId);
    jdbc.update("DELETE FROM project WHERE id=?", projectId);
    jdbc.update("DELETE FROM app_user WHERE id=?", userId);
    jdbc.update("DELETE FROM org WHERE id=?", orgId);
  }

  @Test
  void twoConsumersRaceExactlyOneConsumesAndOneSeesReplay() throws Exception {
    jdbc.update(
        "INSERT INTO org (id,name,domain_patterns,tier,created_by_user_id) VALUES (?,?,?,?,?)",
        orgId,
        "Chat Org",
        "example.com",
        "ENTERPRISE",
        userId);
    jdbc.update(
        "INSERT INTO app_user"
            + " (id,email,first_name,last_name,password_hash,tier,status,org_id)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        userId,
        userId + "@example.com",
        "Chat",
        "User",
        "hash",
        "ENTERPRISE",
        "ACTIVE",
        orgId);
    jdbc.update(
        "INSERT INTO project (id,org_id,name,created_by,ai_provider) VALUES (?,?,?,?,?)",
        projectId,
        orgId,
        "Chat Project",
        userId,
        "DEEPSEEK");
    ProjectChatConfirmation row =
        confirmations.saveAndFlush(
            ProjectChatConfirmation.builder()
                .projectId(projectId)
                .orgId(orgId)
                .actorUserId(userId)
                .action("GENERATE_PROJECT_DOC")
                .capability("SPEND_AI")
                .provider("deepseek")
                .model("deepseek-v4-pro-native")
                .targetJson("{\"docType\":\"SETUP\"}")
                .targetHash("a".repeat(64))
                .scopeHash("b".repeat(64))
                .tokenHash("c".repeat(64))
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
    confirmationId = row.getId();

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Future<ProjectChatConfirmationConsumer.Outcome> first =
          pool.submit(() -> consumeAfterBarrier(ready, start));
      Future<ProjectChatConfirmationConsumer.Outcome> second =
          pool.submit(() -> consumeAfterBarrier(ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(
              ProjectChatConfirmationConsumer.Outcome.CONSUMED,
              ProjectChatConfirmationConsumer.Outcome.REPLAY);
    } finally {
      pool.shutdownNow();
    }
    assertThat(confirmations.findById(confirmationId).orElseThrow().getStatus())
        .isEqualTo(ProjectChatConfirmation.Status.CONSUMED);
  }

  private ProjectChatConfirmationConsumer.Outcome consumeAfterBarrier(
      CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
    return consumer
        .consume(confirmationId, projectId, orgId, userId, "c".repeat(64), "b".repeat(64))
        .outcome();
  }
}
