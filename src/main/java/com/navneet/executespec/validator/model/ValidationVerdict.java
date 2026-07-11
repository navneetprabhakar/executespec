package com.navneet.executespec.validator.model;

public enum ValidationVerdict {
  PASS,
  FAIL,
  WARN,
  /** The validator could not run a required gate; this is not a pass or a fail. */
  UNMEASURED
}
