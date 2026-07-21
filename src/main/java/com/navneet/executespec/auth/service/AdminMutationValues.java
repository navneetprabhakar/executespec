package com.navneet.executespec.auth.service;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * RR-010 (2026-07-21, P1 correctness) — bounded, validated value domains for SUPER_ADMIN mutation
 * endpoints.
 *
 * <p>{@code AppUser.status} / {@code .tier} / {@code .globalRole}, org status/tier and admin
 * run-limit / grant / top-up quantities were persisted straight from a request {@code Map} with no
 * validation, so a typo or a hostile payload could write an invalid authorization/billing state,
 * trip a NOT-NULL constraint, or 500 on a malformed body. These parsers reject an absent, blank or
 * out-of-domain value with a clear {@code 400} before anything is written.
 *
 * <p>The value sets are Strings rather than enums because the columns are Strings and the same
 * tokens are compared as raw strings across the codebase; the correctness win is rejecting garbage,
 * not re-typing the column. Membership covers every value the platform legitimately assigns.
 */
public final class AdminMutationValues {

  private AdminMutationValues() {}

  public static final Set<String> USER_STATUSES =
      Set.of("ACTIVE", "SUSPENDED", "PENDING_VERIFICATION", "DELETED");

  public static final Set<String> TIERS =
      Set.of("FREE", "PRO", "PRO_PLUS", "BYOK", "TEAM", "ENTERPRISE");

  public static final Set<String> GLOBAL_ROLES =
      Set.of("USER", "DEVELOPER", "BILLING_ADMIN", "ORG_ADMIN", "FINANCE", "SUPER_ADMIN");

  public static final Set<String> ORG_STATUSES = Set.of("ACTIVE", "SUSPENDED");

  public static String requireUserStatus(String raw) {
    return requireOneOf(raw, USER_STATUSES, "status");
  }

  public static String requireTier(String raw) {
    return requireOneOf(raw, TIERS, "tier");
  }

  public static String requireGlobalRole(String raw) {
    return requireOneOf(raw, GLOBAL_ROLES, "role");
  }

  public static String requireOrgStatus(String raw) {
    return requireOneOf(raw, ORG_STATUSES, "status");
  }

  private static String requireOneOf(String raw, Set<String> allowed, String field) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Field '" + field + "' is required.");
    }
    String normalized = raw.trim().toUpperCase();
    if (!allowed.contains(normalized)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Invalid " + field + ": '" + raw + "'. Allowed: " + allowed + ".");
    }
    return normalized;
  }

  /** Parse a positive integer (count/quantity), rejecting null, non-numeric and {@code <= 0}. */
  public static int requirePositiveInt(Object raw, String field) {
    if (!(raw instanceof Number n)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Field '" + field + "' is required and must be a number.");
    }
    long v = n.longValue();
    if (v <= 0 || v > Integer.MAX_VALUE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Field '" + field + "' must be a positive integer.");
    }
    return (int) v;
  }

  /**
   * Parse a non-negative long (money in minor units), rejecting null, non-numeric and {@code <0}.
   */
  public static long requireNonNegativeLong(Object raw, String field) {
    if (!(raw instanceof Number n)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Field '" + field + "' is required and must be a number.");
    }
    long v = n.longValue();
    if (v < 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Field '" + field + "' must not be negative.");
    }
    return v;
  }

  /** Optional non-negative integer (e.g. run-limit override); {@code null} passes through. */
  public static Integer optionalNonNegativeInt(Object raw, String field) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Number n)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Field '" + field + "' must be a number or null.");
    }
    long v = n.longValue();
    if (v < 0 || v > Integer.MAX_VALUE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Field '" + field + "' must be a non-negative integer or null.");
    }
    return (int) v;
  }
}
