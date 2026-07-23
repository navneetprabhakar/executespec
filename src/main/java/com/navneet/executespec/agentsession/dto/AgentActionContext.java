package com.navneet.executespec.agentsession.dto;

import java.util.List;

/**
 * Typed blocking context for an agent action proposal.
 *
 * <p>Phase 4 (item 26) of the pre-routing programme: the orchestrator used to signal "you cannot
 * confirm this yet" only through prose in {@code assistantReply}, which forced every client to
 * re-derive confirmability from free text. This record makes the contract explicit and machine
 * readable so a client never has to parse a sentence to decide whether a button may be enabled.
 *
 * <p>The invariant this type exists to protect: a decision whose context is not {@link
 * #confirmable()} must never carry a confirmable {@code create_cloud_run} action.
 *
 * @param requiredContext stable identifiers of the context a caller must supply (for example {@code
 *     project}), never null
 * @param blockingReasons stable machine-readable reason codes for why confirmation is blocked,
 *     never null and empty exactly when {@link #confirmable()} is true
 * @param confirmable true when the associated action may be presented as confirmable
 * @param recommendedAction stable identifier of what the caller should do next (for example {@code
 *     SELECT_PROJECT} or {@code CONFIRM_ACTION})
 * @param projectSelectionRequired true when the caller must select a project/repository first
 */
public record AgentActionContext(
    List<String> requiredContext,
    List<String> blockingReasons,
    boolean confirmable,
    String recommendedAction,
    boolean projectSelectionRequired) {

  /** Stable {@link #requiredContext()} identifier for a missing project/repository selection. */
  public static final String CONTEXT_PROJECT = "project";

  /** Stable {@link #blockingReasons()} code for a missing project/repository selection. */
  public static final String REASON_MISSING_PROJECT = "MISSING_PROJECT_CONTEXT";

  /** Stable {@link #blockingReasons()} code for a caller without run-submission authority. */
  public static final String REASON_MISSING_WRITE_SCOPE = "MISSING_WRITE_SCOPE";

  /** Stable {@link #recommendedAction()} for a blocked proposal needing project selection. */
  public static final String ACTION_SELECT_PROJECT = "SELECT_PROJECT";

  /** Stable {@link #recommendedAction()} for a proposal the caller may confirm. */
  public static final String ACTION_CONFIRM = "CONFIRM_ACTION";

  public AgentActionContext {
    requiredContext = requiredContext == null ? List.of() : List.copyOf(requiredContext);
    blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    if (confirmable && !blockingReasons.isEmpty()) {
      throw new IllegalArgumentException("confirmable context cannot carry blocking reasons");
    }
    if (!confirmable && blockingReasons.isEmpty()) {
      throw new IllegalArgumentException("blocked context must carry at least one blocking reason");
    }
  }

  /** A context that permits confirmation because every required input is already present. */
  public static AgentActionContext ready() {
    return new AgentActionContext(List.of(), List.of(), true, ACTION_CONFIRM, false);
  }

  /** A context that blocks confirmation until the caller selects a project/repository. */
  public static AgentActionContext requiresProjectSelection() {
    return new AgentActionContext(
        List.of(CONTEXT_PROJECT),
        List.of(REASON_MISSING_PROJECT),
        false,
        ACTION_SELECT_PROJECT,
        true);
  }

  /** A context that blocks confirmation because the caller lacks run-submission authority. */
  public static AgentActionContext writeScopeRequired() {
    return new AgentActionContext(
        List.of(), List.of(REASON_MISSING_WRITE_SCOPE), false, ACTION_SELECT_PROJECT, false);
  }
}
