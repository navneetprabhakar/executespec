package com.navneet.executespec.workspacegeneration;

/** Central authority for translating executed checks into an auditable proof-strength decision. */
final class AcceptanceProofPolicy {

  private AcceptanceProofPolicy() {}

  static Decision evaluate(
      AcceptanceProofStrength required,
      boolean buildPassed,
      boolean testsExecuted,
      boolean testsPassed,
      boolean runtimeContractPassed,
      boolean heldOutPassed) {
    AcceptanceProofStrength achieved = AcceptanceProofStrength.STATIC_STRUCTURE;
    if (buildPassed) achieved = AcceptanceProofStrength.BUILD;
    if (buildPassed && testsExecuted && testsPassed) achieved = AcceptanceProofStrength.TEST;
    if (buildPassed && runtimeContractPassed) {
      achieved = AcceptanceProofStrength.RUNTIME_CONTRACT;
    }
    if (buildPassed && runtimeContractPassed && heldOutPassed) {
      achieved = AcceptanceProofStrength.HELD_OUT;
    }
    return new Decision(required, achieved, achieved.satisfies(required));
  }

  record Decision(
      AcceptanceProofStrength required, AcceptanceProofStrength achieved, boolean sufficient) {}
}
