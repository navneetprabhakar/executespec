package com.navneet.executespec.workspacegeneration;

/**
 * Ordered evidence strength required before a BUILD candidate may advance the accepted frontier.
 */
public enum AcceptanceProofStrength {
  STATIC_STRUCTURE,
  BUILD,
  TEST,
  RUNTIME_CONTRACT,
  HELD_OUT;

  public boolean satisfies(AcceptanceProofStrength required) {
    return required != null && ordinal() >= required.ordinal();
  }

  static AcceptanceProofStrength requiredFor(String runtimeSurface, String testRequirement) {
    if ("HTTP_SERVICE".equals(runtimeSurface)) return RUNTIME_CONTRACT;
    return "REQUIRED".equals(testRequirement) ? TEST : BUILD;
  }
}
