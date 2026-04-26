package com.navneet.executespec.standards.enforcement.lint;

import java.util.List;

/**
 * E3.4 — single violation reported by a lint adapter. Maps directly to
 * the JSONB payload stored on {@link com.navneet.executespec.standards.StandardsViolation}.
 *
 * @param file    repo-relative path of the file
 * @param line    1-based line number; 0 when unknown / file-level
 * @param column  1-based column; 0 when unknown
 * @param ruleId  linter-specific rule identifier (e.g. {@code "no-unused-vars"})
 * @param message human-readable message
 */
public record LintViolation(
        String file,
        int line,
        int column,
        String ruleId,
        String message) {}
