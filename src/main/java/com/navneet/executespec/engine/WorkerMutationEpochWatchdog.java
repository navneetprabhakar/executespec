package com.navneet.executespec.engine;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Bounds verification-only loops after a successful workspace mutation.
 *
 * <p>A green verification is useful evidence once for the current mutation epoch. Repeating the
 * same or a different verification without another mutation cannot indefinitely reset progress.
 * This guard is intentionally in-memory: resumed workers begin a fresh bounded window.
 */
final class WorkerMutationEpochWatchdog {

  static final int WARN_AFTER = 4;
  static final int FORCE_AFTER = 8;

  enum Decision {
    NONE,
    NUDGE,
    FORCE
  }

  record Observation(boolean verificationTool, boolean progressAllowed) {}

  private long epoch;
  private int iterationsSinceMutation;
  private boolean verificationCreditAvailable;
  private boolean nudged;
  private final Set<String> verificationSignatures = new HashSet<>();
  private final Set<String> investigationSignatures = new HashSet<>();
  private boolean verificationSeenThisIteration;
  private boolean investigativeProgressThisIteration;

  void recordMutation() {
    epoch++;
    iterationsSinceMutation = 0;
    verificationCreditAvailable = true;
    nudged = false;
    verificationSignatures.clear();
    investigationSignatures.clear();
  }

  /** Returns true only for the first successful verification in the current mutation epoch. */
  boolean recordVerification(String capability, boolean successful) {
    if (epoch == 0) return successful;
    String normalizedCapability =
        capability == null ? "UNKNOWN" : capability.trim().toUpperCase(Locale.ROOT);
    verificationSignatures.add(
        epoch + ":" + normalizedCapability + ":" + (successful ? "SUCCESS" : "FAILURE"));
    if (!successful || !verificationCreditAvailable) return false;
    verificationCreditAvailable = false;
    return true;
  }

  Observation observe(String toolName, String envelope, String callSignature) {
    boolean verification =
        WorkerAgent.isBuildTool(toolName)
            || WorkerAgent.isRunTestsTool(toolName)
            || WorkerAgent.isShellTool(toolName);
    verificationSeenThisIteration |= verification;
    if (isInvestigativeRead(toolName)
        && WorkerAgent.isOkEnvelope(envelope)
        && !WorkerAgent.isFailureEnvelope(envelope)
        && investigationSignatures.add(callSignature)) {
      investigativeProgressThisIteration = true;
    }
    boolean successful =
        verification
            && WorkerAgent.isOkEnvelope(envelope)
            && !WorkerAgent.isFailureEnvelope(envelope)
            && WorkerAgent.envelopeProvesExitZero(toolName, envelope);
    return new Observation(verification, !verification || recordVerification(toolName, successful));
  }

  /** Whether verification novelty must be ignored by the generic progress-v2 detector. */
  boolean hasMutationEpoch() {
    return epoch > 0;
  }

  Decision afterIteration(boolean mutated) {
    if (epoch == 0 || mutated) return finishIteration(Decision.NONE);
    if (investigativeProgressThisIteration) {
      iterationsSinceMutation = 0;
      nudged = false;
      return finishIteration(Decision.NONE);
    }
    if (!verificationSeenThisIteration) return finishIteration(Decision.NONE);
    iterationsSinceMutation++;
    if (iterationsSinceMutation >= FORCE_AFTER) return finishIteration(Decision.FORCE);
    if (!nudged && iterationsSinceMutation >= WARN_AFTER) {
      nudged = true;
      return finishIteration(Decision.NUDGE);
    }
    return finishIteration(Decision.NONE);
  }

  boolean forceAfterIteration(boolean mutated, java.util.List<Message> history) {
    Decision decision = afterIteration(mutated);
    if (decision == Decision.NUDGE) history.add(new UserMessage(nudgeMessage()));
    return decision == Decision.FORCE;
  }

  int distinctVerificationSignatures() {
    return verificationSignatures.size();
  }

  private Decision finishIteration(Decision decision) {
    verificationSeenThisIteration = false;
    investigativeProgressThisIteration = false;
    return decision;
  }

  private static boolean isInvestigativeRead(String toolName) {
    return Set.of(
            "readFile", "searchFiles", "listFiles", "glob", "ast_search", "astSearch", "gitDiff")
        .contains(toolName);
  }

  static String nudgeMessage() {
    return "The workspace has not changed since the last mutation. Re-running verification no"
        + " longer counts as progress. Make a concrete edit if the result is wrong, or call"
        + " completeTask now if the current workspace is verified.";
  }
}
