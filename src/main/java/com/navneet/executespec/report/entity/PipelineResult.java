package com.navneet.executespec.report.entity;

public enum PipelineResult {
  PASSED, // no issues found
  PASSED_WITH_FIXES, // issues found AND all auto-fixed successfully
  NEEDS_ATTENTION, // issues found, some/all NOT resolved
  FAILED // critical unresolved failures (e.g. compilation failure)
}
