package com.navneet.executespec.sandbox;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The run-sandbox lifecycle state machine (V307).
 *
 * <p>This enum is the single definition of which transitions exist. {@link RunSandboxStore}
 * consults it before every write and the V307 {@code ck_run_sandbox_state} constraint plus the
 * per-transition SQL predicates enforce the same set at the database, so an illegal transition is
 * rejected by the store rather than avoided by convention.
 *
 * <p>The graph is deliberately small. Every non-terminal state has a path to a terminal state that
 * does not require the runtime to be dispatchable, because {@code sandbox.runtime.dispatch.enabled}
 * may be turned off while sandboxes are live and cancellation, cleanup and reconciliation must
 * still complete.
 */
public enum SandboxState {

  /** Lifecycle row admitted. No runtime has claimed it and nothing has executed. */
  REQUESTED,

  /** A runtime holds the lease and is preparing the sandbox. */
  PROVISIONING,

  /** The sandbox is live and usable. */
  READY,

  /** Cancellation was requested against a sandbox a runtime is still responsible for. */
  CANCEL_REQUESTED,

  /** Teardown in progress: the runtime is destroying the sandbox and settling artifacts. */
  RELEASING,

  /** Terminal: the sandbox completed and was released cleanly. */
  TERMINATED,

  /** Terminal: released because a person or policy cancelled it. */
  CANCELLED,

  /** Terminal: released because its TTL elapsed. */
  EXPIRED,

  /** Terminal: released after a classified failure. */
  FAILED;

  private static final Map<SandboxState, Set<SandboxState>> ALLOWED =
      Map.of(
          REQUESTED,
          EnumSet.of(PROVISIONING, CANCELLED, EXPIRED, FAILED),
          PROVISIONING,
          EnumSet.of(READY, CANCEL_REQUESTED, RELEASING, FAILED),
          READY,
          EnumSet.of(CANCEL_REQUESTED, RELEASING, FAILED),
          CANCEL_REQUESTED,
          EnumSet.of(RELEASING, CANCELLED, FAILED),
          RELEASING,
          EnumSet.of(TERMINATED, CANCELLED, EXPIRED, FAILED),
          TERMINATED,
          EnumSet.noneOf(SandboxState.class),
          CANCELLED,
          EnumSet.noneOf(SandboxState.class),
          EXPIRED,
          EnumSet.noneOf(SandboxState.class),
          FAILED,
          EnumSet.noneOf(SandboxState.class));

  /** Terminal states hold no lease and accept no further transition. */
  public boolean terminal() {
    return this == TERMINATED || this == CANCELLED || this == EXPIRED || this == FAILED;
  }

  /** States in which a runtime owner and an unexpired lease must exist. */
  public boolean leaseHeld() {
    return this == PROVISIONING || this == READY || this == CANCEL_REQUESTED || this == RELEASING;
  }

  public Set<SandboxState> allowedNext() {
    return ALLOWED.get(this);
  }

  public boolean canTransitionTo(SandboxState next) {
    return next != null && ALLOWED.get(this).contains(next);
  }

  /** The states that may legally precede {@code next}. Used to build fenced SQL predicates. */
  public static Set<SandboxState> predecessorsOf(SandboxState next) {
    EnumSet<SandboxState> from = EnumSet.noneOf(SandboxState.class);
    for (SandboxState state : values()) {
      if (state.canTransitionTo(next)) {
        from.add(state);
      }
    }
    return from;
  }

  /** Rejects an undefined transition before any database write is attempted. */
  public void requireTransitionTo(SandboxState next) {
    if (!canTransitionTo(next)) {
      throw new IllegalSandboxTransitionException(this, next);
    }
  }
}
