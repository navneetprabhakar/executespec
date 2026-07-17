package com.navneet.executespec.specchat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomically consumes a confirmation in its own transaction before any external side effect. */
@Service
public class ProjectChatConfirmationConsumer {

  public enum Outcome {
    CONSUMED,
    NOT_FOUND,
    TENANT_MISMATCH,
    ACTOR_MISMATCH,
    SCOPE_MISMATCH,
    TOKEN_MISMATCH,
    EXPIRED,
    REPLAY
  }

  public record Attempt(Outcome outcome, ProjectChatConfirmation confirmation) {}

  private final ProjectChatConfirmationRepository repository;
  private final Clock clock;

  @Autowired
  public ProjectChatConfirmationConsumer(ProjectChatConfirmationRepository repository) {
    this(repository, Clock.systemUTC());
  }

  ProjectChatConfirmationConsumer(ProjectChatConfirmationRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Attempt consume(
      UUID id,
      String projectId,
      String orgId,
      String actorUserId,
      String tokenHash,
      String scopeHash) {
    ProjectChatConfirmation confirmation = repository.findByIdForUpdate(id).orElse(null);
    if (confirmation == null) return new Attempt(Outcome.NOT_FOUND, null);
    if (!constantTimeEquals(confirmation.getProjectId(), projectId)
        || !constantTimeEquals(confirmation.getOrgId(), orgId)) {
      return new Attempt(Outcome.TENANT_MISMATCH, confirmation);
    }
    if (!constantTimeEquals(confirmation.getActorUserId(), actorUserId)) {
      return new Attempt(Outcome.ACTOR_MISMATCH, confirmation);
    }
    if (confirmation.getStatus() == ProjectChatConfirmation.Status.CONSUMED) {
      return new Attempt(Outcome.REPLAY, confirmation);
    }
    Instant now = clock.instant();
    if (confirmation.getStatus() == ProjectChatConfirmation.Status.EXPIRED
        || !confirmation.getExpiresAt().isAfter(now)) {
      confirmation.setStatus(ProjectChatConfirmation.Status.EXPIRED);
      repository.save(confirmation);
      return new Attempt(Outcome.EXPIRED, confirmation);
    }
    if (!constantTimeEquals(confirmation.getScopeHash(), scopeHash)) {
      return new Attempt(Outcome.SCOPE_MISMATCH, confirmation);
    }
    if (!constantTimeEquals(confirmation.getTokenHash(), tokenHash)) {
      return new Attempt(Outcome.TOKEN_MISMATCH, confirmation);
    }
    confirmation.setStatus(ProjectChatConfirmation.Status.CONSUMED);
    confirmation.setConsumedAt(now);
    repository.save(confirmation);
    return new Attempt(Outcome.CONSUMED, confirmation);
  }

  private static boolean constantTimeEquals(String expected, String actual) {
    byte[] left = String.valueOf(expected).getBytes(StandardCharsets.UTF_8);
    byte[] right = String.valueOf(actual).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(left, right);
  }
}
