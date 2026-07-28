package com.navneet.executespec.repositoryexplorer;

/** Provider-free decision boundary for whether the optional explorer may be invoked. */
public final class ExplorerInvocationPolicy {

  public Decision decide(Signals signals) {
    if (signals == null) throw new IllegalArgumentException("signals are required");
    if (!signals.enabled()) return new Decision(false, "DISABLED", signals.arm());
    if (signals.arm() == ExplorerExperimentArm.CONTROL) {
      return new Decision(false, "CONTROL_ARM", signals.arm());
    }
    if (signals.arm() == ExplorerExperimentArm.PAIRED) {
      return new Decision(true, "PAIRED_ARM", signals.arm());
    }
    if (signals.deterministicLocalizationAmbiguous()) {
      return new Decision(true, "AMBIGUOUS_LOCALIZATION", signals.arm());
    }
    if (signals.repositoryFileCount() >= signals.largeRepositoryFileThreshold()) {
      return new Decision(true, "LARGE_REPOSITORY", signals.arm());
    }
    if (signals.unknownIdentifierCount() > 0) {
      return new Decision(true, "UNKNOWN_IDENTIFIERS", signals.arm());
    }
    return new Decision(false, "DETERMINISTIC_EVIDENCE_SUFFICIENT", signals.arm());
  }

  public record Signals(
      boolean enabled,
      ExplorerExperimentArm arm,
      boolean deterministicLocalizationAmbiguous,
      long repositoryFileCount,
      long largeRepositoryFileThreshold,
      int unknownIdentifierCount) {

    public Signals {
      if (arm == null) throw new IllegalArgumentException("arm is required");
      if (repositoryFileCount < 0) {
        throw new IllegalArgumentException("repositoryFileCount cannot be negative");
      }
      if (largeRepositoryFileThreshold <= 0) {
        throw new IllegalArgumentException("largeRepositoryFileThreshold must be positive");
      }
      if (unknownIdentifierCount < 0) {
        throw new IllegalArgumentException("unknownIdentifierCount cannot be negative");
      }
    }
  }

  public record Decision(boolean invokeExplorer, String reason, ExplorerExperimentArm arm) {
    public Decision {
      if (reason == null || reason.isBlank())
        throw new IllegalArgumentException("reason is required");
      if (arm == null) throw new IllegalArgumentException("arm is required");
    }
  }
}
