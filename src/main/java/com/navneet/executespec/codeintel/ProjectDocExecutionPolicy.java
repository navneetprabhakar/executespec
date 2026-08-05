package com.navneet.executespec.codeintel;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import com.navneet.executespec.executioncontext.LlmExecutionBudget;

/** Bounded execution-context identity and budget for one project-document attempt. */
final class ProjectDocExecutionPolicy {

  static final LlmExecutionBudget ATTEMPT_BUDGET =
      new LlmExecutionBudget(null, null, 200_000L, BigDecimal.valueOf(3), 2);

  private ProjectDocExecutionPolicy() {}

  static String contextId(UUID nonce) {
    return "project-doc:" + Objects.requireNonNull(nonce, "nonce");
  }

  static String durableContextId(String durableAttemptIdentity) {
    String identity = Objects.requireNonNull(durableAttemptIdentity, "identity").trim();
    if (!identity.matches("[0-9a-fA-F-]{36}:[1-9][0-9]*")) {
      throw new IllegalArgumentException("durable project-doc attempt identity is invalid");
    }
    return "project-doc:" + identity;
  }
}
