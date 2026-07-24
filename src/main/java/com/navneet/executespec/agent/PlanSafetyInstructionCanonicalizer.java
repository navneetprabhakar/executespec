package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rewrites contradictory free-form planner instructions before they reach a worker. */
final class PlanSafetyInstructionCanonicalizer {

  private PlanSafetyInstructionCanonicalizer() {}

  record RewriteResult(String description, List<String> constraints, int rewrites) {}

  /**
   * SQLite ignores row-lock syntax, so positive instructions must be removed, not qualified later.
   */
  static RewriteResult rewriteUnsafeSqliteConcurrency(
      String description, List<String> constraints) {
    String rewrittenDescription =
        description
            .replaceAll(
                "(?i)(?:via\\s+)?row-level\\s+lock\\s*\\(\\s*SELECT\\s*(?:\\.\\.\\.|…)?\\s+FOR\\s+UPDATE\\s*\\)",
                "using a SQLite-effective atomic mutation (BEGIN IMMEDIATE or a conditional UPDATE"
                    + " with affected-row verification)")
            .replaceAll(
                "(?i)optimistic/pessimistic locking",
                "a SQLite-effective conditional atomic UPDATE");
    if (containsUnsafePositiveSelectForUpdate(rewrittenDescription)) {
      rewrittenDescription =
          rewrittenDescription
              .replaceAll(
                  "(?i)SELECT\\s*(?:\\.\\.\\.|…)?\\s+FOR\\s+UPDATE",
                  "BEGIN IMMEDIATE or a conditional UPDATE with affected-row verification")
              .replaceAll(
                  "(?i)(?:with|using|use)\\s+(?:a\\s+)?FOR\\s+UPDATE(?:\\s+lock)?",
                  "using BEGIN IMMEDIATE or a conditional UPDATE with affected-row verification");
    }
    if (containsUnsafePositiveWithForUpdate(rewrittenDescription)) {
      rewrittenDescription =
          rewrittenDescription.replaceAll(
              "(?i)(?:SQLAlchemy\\s+)?with_for_update\\s*(?:\\(\\s*\\))?",
              "a conditional UPDATE with affected-row verification");
    }

    int rewrites = description.equals(rewrittenDescription) ? 0 : 1;
    List<String> rewrittenConstraints = new ArrayList<>(constraints.size());
    for (String constraint : constraints) {
      if (containsUnsafePositiveSelectForUpdate(constraint)
          || containsUnsafePositiveWithForUpdate(constraint)) {
        rewrites++;
      } else {
        rewrittenConstraints.add(constraint);
      }
    }
    return new RewriteResult(rewrittenDescription, rewrittenConstraints, rewrites);
  }

  /** Canonicalizes planner prose that would otherwise override a later hard constraint. */
  static RewriteResult rewriteContradictoryExecutableInstructions(
      String description,
      List<String> constraints,
      boolean verifiedPrincipalRequired,
      boolean secureCredentialRequired,
      boolean invalidDoubleCancelRequired,
      boolean blackBoxHttpRequired) {
    String rewrittenDescription = safe(description);
    if (verifiedPrincipalRequired) {
      rewrittenDescription =
          rewrittenDescription
              .replaceAll(
                  "(?i)extracting customer identity from a secure header like Authorization or"
                      + " X-Customer-ID",
                  "verifying a credential from an Authorization/API-key header and resolving the"
                      + " stable customer principal server-side")
              .replaceAll(
                  "(?i)customer[- ]id header extraction",
                  "verified bearer/API credential authentication that resolves the customer"
                      + " principal server-side")
              .replaceAll(
                  "(?i)reads? (the )?Idempotency-Key \\+ X-Customer-Id headers",
                  "reads Idempotency-Key plus a verified bearer/API credential and derives the"
                      + " customer principal server-side")
              .replaceAll(
                  "(?i)authenticated customer \\(X-Customer-Id header\\)",
                  "authenticated customer derived from a verified bearer/API credential")
              .replaceAll("(?i)X-Customer-ID(?: header)?", "a verified bearer/API credential");
    }
    if (secureCredentialRequired) {
      rewrittenDescription = rewriteCredentialInstructions(rewrittenDescription);
    }
    if (invalidDoubleCancelRequired) {
      rewrittenDescription =
          rewrittenDescription
              .replaceAll(
                  "(?i)idempotent on already[- ]cancelled",
                  "rejects an already-cancelled reservation with the stable invalid-state error"
                      + " without restoring stock again")
              .replaceAll(
                  "(?i)idempotent on repeated cancel",
                  "rejects repeated cancellation with the stable invalid-state error while"
                      + " restoring stock exactly once")
              .replaceAll(
                  "(?i)second cancel returns the stored response",
                  "second cancel returns the stable invalid-state error while stock remains"
                      + " restored exactly once");
    }
    if (blackBoxHttpRequired) {
      rewrittenDescription = rewriteBlackBoxInstructions(rewrittenDescription);
    }

    int rewrites = description.equals(rewrittenDescription) ? 0 : 1;
    List<String> rewrittenConstraints = new ArrayList<>(constraints.size());
    for (String constraint : constraints) {
      String rewritten =
          rewriteConstraint(
              constraint,
              verifiedPrincipalRequired,
              secureCredentialRequired,
              invalidDoubleCancelRequired,
              blackBoxHttpRequired);
      if (!constraint.equals(rewritten)) rewrites++;
      rewrittenConstraints.add(rewritten);
    }
    return new RewriteResult(rewrittenDescription, rewrittenConstraints, rewrites);
  }

  private static String rewriteCredentialInstructions(String value) {
    return value
        .replaceAll("\\bapi_key\\b", "api_key_hash")
        .replaceAll(
            "(?i)lifespan creating tables and seeding test products/customers",
            "lifespan running Alembic migrations without runtime credential/data seeding")
        .replaceAll(
            "(?i)Seed one test product \\(stock=10\\) and one test customer"
                + " \\(api_key_hash=test-key\\)\\.",
            "Create equivalent product/customer data only in isolated test fixtures using"
                + " randomly generated credentials.")
        .replaceAll(
            "(?i)validating against Customer table",
            "verifying the presented credential against the stored one-way verifier using"
                + " constant-time comparison")
        .replaceAll(
            "(?i)seeded test data \\(customer api_key_hash, product\\)",
            "credential provisioning and isolated test-fixture data")
        .replaceAll(
            "(?i)seeds Product rows \\(non-negative stock\\) and a test Customer with a"
                + " generated API key \\(hashed\\)",
            "may provision Product rows with non-negative stock but does not create runtime"
                + " customer credentials; customer credentials are provisioned explicitly"
                + " outside startup")
        .replaceAll(
            "(?i)Test credentials must be generated at startup",
            "Runtime startup must not generate customer credentials; test credentials are"
                + " generated only inside isolated fixtures")
        .replaceAll(
            "(?i)validate against a configurable mapping to customer_id",
            "verify against the externally configured mapping in memory without persisting or"
                + " logging raw credentials, and resolve its stable customer_id")
        .replaceAll(
            "(?i)IdempotencyRecord stores the SHA-256 hash of the API key \\(not plaintext\\)"
                + " as the customer_id lookup key",
            "Credential records store a one-way API-key verifier associated with a stable"
                + " customer_id; IdempotencyRecord references that customer_id")
        .replaceAll(
            "(?i)API_KEYS env var format", "explicit fail-closed external credential-map format");
  }

  private static String rewriteBlackBoxInstructions(String value) {
    return value
        .replaceAll(
            "(?i)not\\s+(?:just\\s+)?(?:an?\\s+)?(?:FastAPI\\s+)?TestClient",
            "not an in-process HTTP test client")
        .replaceAll("(?i)not just (?:FastAPI\\s+)?TestClient", "not an in-process HTTP test client")
        .replaceAll(
            "(?i)(?:do not|must not|never) use (?:FastAPI\\s+)?TestClient(?: to simulate"
                + " HTTP)?",
            "do not use an in-process HTTP test client as acceptance proof")
        .replaceAll(
            "(?i)(?:FastAPI\\s+)?TestClient",
            "a real HTTP client against the documented server launched as a subprocess")
        .replaceAll(
            "(?i)yield httpx\\.AsyncClient against the FastAPI app",
            "launch the documented uvicorn server as a subprocess and yield"
                + " httpx.AsyncClient against its loopback HTTP URL")
        .replaceAll(
            "(?i)with dependency overrides providing test API keys",
            "with isolated generated test credentials provisioned before server startup")
        .replaceAll(
            "(?i)Tests run in isolated transactions\\.",
            "Each test uses an independently migrated database or uniquely isolated test records.");
  }

  private static String rewriteConstraint(
      String constraint,
      boolean verifiedPrincipalRequired,
      boolean secureCredentialRequired,
      boolean invalidDoubleCancelRequired,
      boolean blackBoxHttpRequired) {
    String rewritten = constraint;
    if (verifiedPrincipalRequired) {
      rewritten =
          rewritten
              .replaceAll(
                  "(?i)authenticated customer \\(X-Customer-Id header\\)",
                  "authenticated customer derived from a verified bearer/API credential")
              .replaceAll("(?i)X-Customer-ID(?: header)?", "a verified bearer/API credential");
    }
    if (secureCredentialRequired) {
      rewritten =
          rewritten
              .replaceAll("\\bapi_key\\b", "api_key_hash")
              .replaceAll(
                  "(?i)Test credentials must be generated at startup",
                  "Runtime startup must not generate customer credentials; test credentials"
                      + " are generated only inside isolated fixtures")
              .replaceAll(
                  "(?i)IdempotencyRecord stores the SHA-256 hash of the API key \\(not"
                      + " plaintext\\) as the customer_id lookup key",
                  "Credential records store a one-way API-key verifier associated with a stable"
                      + " customer_id; IdempotencyRecord references that customer_id");
    }
    if (invalidDoubleCancelRequired) {
      rewritten =
          rewritten.replaceAll(
              "(?i)idempotent on repeated cancel",
              "reject repeated cancellation with the stable invalid-state error and restore"
                  + " stock exactly once");
    }
    if (blackBoxHttpRequired
        && !rewritten.contains("supplemental and cannot be the only acceptance proof")) {
      rewritten = rewriteBlackBoxConstraint(rewritten);
    }
    return rewritten;
  }

  private static String rewriteBlackBoxConstraint(String value) {
    return value
        .replaceAll(
            "(?i)not\\s+(?:just\\s+)?(?:an?\\s+)?(?:FastAPI\\s+)?TestClient",
            "not an in-process HTTP test client")
        .replaceAll("(?i)not just (?:FastAPI\\s+)?TestClient", "not an in-process HTTP test client")
        .replaceAll(
            "(?i)(?:do not|must not|never) use (?:FastAPI\\s+)?TestClient(?: to simulate HTTP)?",
            "do not use an in-process HTTP test client as acceptance proof")
        .replaceAll(
            "(?i)(?:FastAPI\\s+)?TestClient",
            "a real HTTP client against the documented server launched as a subprocess");
  }

  private static boolean containsUnsafePositiveSelectForUpdate(String value) {
    String normalized =
        safe(value).toLowerCase(Locale.ROOT).replace("…", " ").replaceAll("\\s*\\.\\.\\.\\s*", " ");
    if (!normalized.contains("select for update")
        && !normalized.contains("for update lock")
        && !normalized.contains("use for update")) return false;
    return !normalized.contains("does not provide effective")
        && !normalized.contains("not effective")
        && !normalized.contains("ineffective")
        && !normalized.contains("must not use")
        && !normalized.contains("do not use");
  }

  private static boolean containsUnsafePositiveWithForUpdate(String value) {
    String normalized = safe(value).toLowerCase(Locale.ROOT);
    if (!normalized.contains("with_for_update")) return false;
    return !normalized.contains("not effective")
        && !normalized.contains("ineffective")
        && !normalized.contains("must not use")
        && !normalized.contains("do not use");
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
