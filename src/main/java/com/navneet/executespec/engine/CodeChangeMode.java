package com.navneet.executespec.engine;

/** Mode-specific policy identity supplied to the shared code-change loop. */
public enum CodeChangeMode {
  BUILD,
  PATCH,
  TERMINAL,
  SPECIALIZED
}
