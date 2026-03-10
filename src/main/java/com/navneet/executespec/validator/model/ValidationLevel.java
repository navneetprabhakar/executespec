package com.navneet.executespec.validator.model;

/**
 * Controls how many gates are executed. Gates run in order and stop at this level.
 * SYNTAX: syntax check only (fastest)
 * TYPE: syntax + type/compile check
 * LINT: syntax + type + lint
 * TEST: syntax + type + lint + unit tests
 * FULL: all gates
 */
public enum ValidationLevel {
    SYNTAX,
    TYPE,
    LINT,
    TEST,
    FULL
}
