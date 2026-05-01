package com.navneet.executespec.reviewer.model;

public enum ReviewVerdict {
  APPROVED, // no issues or only minor issues
  APPROVED_WITH_NOTES, // minor issues reported but not blocking
  NEEDS_FIXES, // critical issues found, some/all auto-fixed
  REJECTED // >5 critical issues or README missing — needs human attention
}
