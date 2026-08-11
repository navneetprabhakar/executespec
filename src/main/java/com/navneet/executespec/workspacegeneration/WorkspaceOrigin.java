package com.navneet.executespec.workspacegeneration;

/**
 * Immutable admission fact describing how a run workspace was obtained.
 *
 * <p>This value is decided from the submitted run boundary and repository admission result. It must
 * never be rediscovered later by inspecting incidental files in the workspace.
 */
public enum WorkspaceOrigin {
  GREENFIELD,
  BROWNFIELD,
  ITERATION,
  MULTI_REPOSITORY;

  public boolean requiresBootstrap() {
    return this == GREENFIELD;
  }

  public static WorkspaceOrigin parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("WORKSPACE_ORIGIN_MISSING");
    }
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("WORKSPACE_ORIGIN_INVALID:" + value, invalid);
    }
  }
}
