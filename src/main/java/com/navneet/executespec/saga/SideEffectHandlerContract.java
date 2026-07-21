package com.navneet.executespec.saga;

import java.util.Arrays;

/**
 * Versioned replay contract for every executable outbox handler.
 *
 * <p>The outbox is at-least-once: a process can crash after an external API accepted a request but
 * before the terminal database update commits. Consequently, a handler is admitted here only when
 * replaying the same stable {@code idempotency_key} is safe. Adding an enum value requires a
 * handler-level regression test for its stated idempotency strategy.
 */
public enum SideEffectHandlerContract {
  GCS_ARCHIVE(
      "GCS_ARCHIVE",
      "v1",
      "Overwrite the deterministic {environment}/workspace/{runId}.zip object and stop when the"
          + " run already records that key"),
  COMPENSATE_GITHUB_BRANCH(
      "COMPENSATE_GITHUB_BRANCH",
      "v1",
      "Delete the exact branch; GitHub 404/422 (already absent) is success and protected branches"
          + " are refused");

  private final String effectType;
  private final String version;
  private final String idempotencyStrategy;

  SideEffectHandlerContract(String effectType, String version, String idempotencyStrategy) {
    this.effectType = effectType;
    this.version = version;
    this.idempotencyStrategy = idempotencyStrategy;
  }

  public String effectType() {
    return effectType;
  }

  public String version() {
    return version;
  }

  public String idempotencyStrategy() {
    return idempotencyStrategy;
  }

  public static SideEffectHandlerContract require(String effectType) {
    return Arrays.stream(values())
        .filter(contract -> contract.effectType.equals(effectType))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No idempotent side-effect handler contract for: " + effectType));
  }
}
