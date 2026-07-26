package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;
import static com.navneet.executespec.agent.TaskExpansionPlanContract.*;
import static com.navneet.executespec.agent.TaskExpansionSemanticContract.containsAny;
import static com.navneet.executespec.agent.TaskExpansionSemanticContract.hasPositiveInstruction;
import static com.navneet.executespec.agent.TaskExpansionTextNormalizer.installsDependenciesInsideBuildOrTest;

import java.util.List;
import java.util.Locale;

/** Detects contradictions between an expanded task description and its binding constraints. */
final class TaskExpansionContradictionValidator {
  private TaskExpansionContradictionValidator() {}

  static void addViolations(PlannedTask task, Plan plan, List<String> violations) {
    String description = safe(task.description()).toLowerCase(Locale.ROOT);
    String constraints =
        String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints())
            .toLowerCase(Locale.ROOT);

    boolean pureBuild =
        constraints.contains("build and test commands must never create an environment or run pip");
    if (pureBuild && installsDependenciesInsideBuildOrTest(description)) {
      violations.add(
          "Task description installs dependencies or creates an environment inside a build/test"
              + " command that must use preinstalled dependencies");
    }
    boolean migrationsOnly =
        constraints.contains("do not fall back to orm schema auto-creation")
            || constraints.contains("must not call orm create_all");
    if (migrationsOnly
        && hasPositiveInstruction(description, "create_all", "create all", "manually create")) {
      violations.add(
          "Task description bypasses the required migration path with ORM/manual schema creation");
    }
    boolean blackBoxHttp =
        constraints.contains("exercise public behavior over real http")
            || constraints.contains("real http client against the documented server");
    if (blackBoxHttp
        && description.contains("in-memory sqlite")
        && hasPositiveInstruction(description, "in-memory sqlite")) {
      violations.add(
          "Real-HTTP subprocess tests require a shared temporary file database, not in-memory"
              + " SQLite");
    }
    if (blackBoxHttp
        && hasPositiveInstruction(
            description,
            "not a subprocess",
            "without a subprocess",
            "against app factory",
            "against the app factory",
            "testclient",
            "app=app",
            "get_db override",
            "dependency override")) {
      violations.add(
          "Real-HTTP acceptance contradicts the required subprocess boundary with an in-process"
              + " client or app-factory instruction");
    }
    boolean invalidRepeatedCancellation =
        constraints.contains("second cancellation must return the stable invalid-state error")
            || constraints.contains("any second release/cancel returns invalid_state");
    if (invalidRepeatedCancellation
        && description.matches(
            "(?s).*(?:second (?:cancel|release)|double (?:cancellation|release)|repeated"
                + " (?:cancellation|release)|already released)[^.\\n]{0,180}"
                + "(?:200|success|stored response|same response|idempotent response).*")) {
      violations.add(
          "Repeated cancellation must return an invalid-state response instead of replaying"
              + " success");
    }
    String taskIdentity =
        (safe(task.title())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
            .toLowerCase(Locale.ROOT);
    boolean rootBuild =
        constraints.contains("the root ./build.sh must compile/import-check")
            || containsAny(taskIdentity, "build.sh", "run_tests.sh");
    if (rootBuild && rootBuildSectionChangesDirectory(description)) {
      violations.add(
          "Root build execution changes working directory before root-relative commands");
    }
    boolean requiredHeader = constraints.contains("idempotency-key is a required http header");
    if (requiredHeader && PlanSafetyTextSupport.makesIdempotencyHeaderOptional(description)) {
      violations.add("Task description makes the required Idempotency-Key header optional");
    }
    IdempotencyResponseSemanticValidator.addViolations(description, constraints, violations);
    boolean canonicalFrontendOrderPayload =
        constraints.contains("ordercreate items use product_id and warehouse_id");
    if (canonicalFrontendOrderPayload
        && hasPositiveInstruction(description, "product_sku", "warehouse_code", "customer_name")) {
      violations.add(
          "Frontend order consumer contradicts the canonical backend payload with alternate"
              + " identity fields");
    }
    if (rootBuild && containsNonCanonicalBuildCommand(description)) {
      violations.add("Root verification task contains non-canonical build or test commands");
    }
    boolean canonicalIdempotencyRecord =
        constraints.contains("one exact idempotencyrecord contract everywhere: key, scope");
    boolean forbidsVersionMigration = constraints.contains("must not create a versioned migration");
    boolean touchesVersionMigration =
        task.touchesFiles() != null
            && task.touchesFiles().stream()
                .anyMatch(PlanSafetyMigrationSupport::isVersionMigrationPath);
    if (forbidsVersionMigration
        && (touchesVersionMigration
            || hasPositiveInstruction(
                description,
                "create a versioned migration",
                "generate a versioned migration",
                "own a versioned migration",
                "create the initial migration",
                "generate the initial migration",
                "alembic revision"))) {
      violations.add(
          "Task is migration-runtime-only but still owns or instructs creation of a versioned"
              + " migration");
    }
    if (canonicalIdempotencyRecord
        && constraints.contains("must not poll")
        && hasIdempotencyPollingInstruction(description)) {
      violations.add("Idempotency task polls despite the binding writer-serialization contract");
    }
    if (canonicalIdempotencyRecord && assignsReplayOwnershipToOrder(constraints)) {
      violations.add(
          "Hard constraints assign replay ownership to Order despite the canonical"
              + " IdempotencyRecord contract");
    }
    if (canonicalIdempotencyRecord
        && (usesIdempotencyRecordAlias(description, "key_hash")
            || usesIdempotencyRecordAlias(description, "idempotency_key")
            || usesIdempotencyRecordAlias(description, "scope_hash"))) {
      violations.add(
          "Task description defines IdempotencyRecord fields outside the canonical key/scope"
              + " contract");
    }
    if (canonicalIdempotencyRecord
        && definesIdempotencyRecordSchema(description)
        && !containsCompleteIdempotencyRecordSchema(description)) {
      violations.add("Task description defines an incomplete canonical IdempotencyRecord schema");
    }
    if (canonicalIdempotencyRecord
        && (description.matches("(?s).*\\bstate\\s*={1,2}\\s*`?pending`?\\b.*")
            || PlanSafetyReservationTextSupport.hasPendingIdempotencyReplay(description))) {
      violations.add("IdempotencyRecord state machine uses PENDING outside PROCESSING/COMPLETED");
    }
    if (canonicalIdempotencyRecord
        && usesBusinessStateForIdempotencyRecord(safe(task.description()))) {
      violations.add(
          "IdempotencyRecord state machine uses a business state outside PROCESSING/COMPLETED");
    }
    boolean exactOrderLifecycle =
        constraints.contains("order.status lifecycle values are exactly")
            && constraints.contains("cancelled");
    if (exactOrderLifecycle
        && description.matches(
            "(?is).*(?:add|define|persist|model)[^.\\n]{0,120}\\border\\b[^.\\n]{0,120}"
                + "\\bstate\\b.*")) {
      violations.add("Order producer persists state instead of the canonical status field");
    }
    if (exactOrderLifecycle
        && description.matches("(?is).*\\bstatus\\s*(?:becomes|→|->|=)\\s*`?CANCEL`?\\b.*")) {
      violations.add("Order consumer uses CANCEL status instead of canonical CANCELLED");
    }
    String taskEvidence =
        (safe(task.title())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
            .toLowerCase(Locale.ROOT);
    String requirements =
        plan.requirements() == null
            ? ""
            : plan.requirements().stream()
                .map(requirement -> safe(requirement == null ? null : requirement.text()))
                .collect(java.util.stream.Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
    boolean realBrowserJourney =
        containsAny(taskEvidence, "end-to-end", "e2e", "playwright", "browser")
            && containsAny(
                requirements,
                "real backend and frontend",
                "backend and frontend",
                "frontend and backend");
    if (realBrowserJourney
        && description.matches(
            "(?is).*(?:setup\\s+via\\s+api|direct\\s+api\\s+calls?\\s+in\\s+setup|"
                + "use\\s+(?:httpx|axios|requests)[^.\\n]{0,160}\\b(?:create|seed|setup|verify)|"
                + "verify\\s+via\\s+api).*")) {
      violations.add("Browser E2E task replaces required UI workflow with direct API operations");
    }
    if (description.matches("(?s).*(?:^|[:\\n])\\s*\\d+\\.\\s*(?=\\d+\\.).*")) {
      violations.add("Task description contains empty numbered implementation steps");
    }
    if (description.matches(
        "(?is).*\\b(?:use|list|search|load|fetch|get)[a-z0-9_]*\\s*\\([^)]*\\)"
            + "[^.\\n]{0,180}\\b(?:fetches|calls|loads?|reads?)\\s+post\\s*/.*")) {
      violations.add(
          task.id()
              + ": read-oriented client or hook uses POST for a collection read; bind it to the"
              + " declared GET producer");
    }
    addQuantityInvariantViolations(task, plan, taskIdentity, description, constraints, violations);
    for (String path : TaskExpansionRouteContract.ambiguousDuplicateReadRoutes(description)) {
      violations.add(
          "one duplicate GET route "
              + path
              + " is assigned both collection and item-read semantics");
    }
  }

  /**
   * Keeps the writer-serialization check clause-local. A task can legitimately poll a health
   * endpoint or retry an HTTP assertion while also consuming the canonical IdempotencyRecord
   * contract; only waiting on another transaction's record is contradictory.
   */
  private static boolean hasIdempotencyPollingInstruction(String description) {
    for (String clause :
        safe(description).split("(?<=[.!?;])(?:\\s+|$)|\\R+|\\s*,\\s*(?:then|and\\s+then)\\s+")) {
      String normalized = clause.toLowerCase(Locale.ROOT);
      if (!containsAny(normalized, "poll", "sleep", "retry loop")) continue;
      if (!containsAny(
          normalized,
          "idempotency",
          "processing",
          "uncommitted",
          "transaction",
          "replay record",
          "claim record")) continue;
      if (hasPositiveInstruction(normalized, "poll", "sleep", "retry loop")) return true;
    }
    return false;
  }

  private static boolean usesBusinessStateForIdempotencyRecord(String description) {
    java.util.regex.Matcher businessState =
        java.util.regex.Pattern.compile("\\b(DRAFT|RESERVED|COMMITTED|CANCELLED)\\b")
            .matcher(description);
    while (businessState.find()) {
      int clauseStart =
          Math.max(
              0,
              Math.max(
                  description.lastIndexOf('.', businessState.start()),
                  description.lastIndexOf('\n', businessState.start())));
      int clauseEnd = description.indexOf('.', businessState.end());
      if (clauseEnd < 0) clauseEnd = description.length();
      String clause = description.substring(clauseStart, clauseEnd);
      int stateOffset = businessState.start() - clauseStart;
      String before = clause.substring(0, stateOffset);
      String after = clause.substring(stateOffset);
      String state = java.util.regex.Pattern.quote(businessState.group());
      boolean ownedByOrder =
          before.matches("(?is).*\\border(?:\\s+record)?(?:\\.|\\s+)(?:status|state)\\b.{0,80}")
              || (before + after)
                  .matches(
                      "(?is).*\\border(?:\\s+record)?\\b.{0,100}\\b"
                          + state
                          + "\\b\\s+(?:status|state)\\b.*");
      if (ownedByOrder) continue;
      String localBefore =
          before.substring(Math.max(0, before.length() - 220)).toLowerCase(Locale.ROOT);
      if (localBefore.matches(
          "(?is).*\\bidempotency\\s*record\\b.{0,200}"
              + "(?:\\b(?:state|status)\\b|\\b(?:insert|create|update|set|transition)\\b|"
              + "\\bresponse_status\\b.{0,40}\\bwhen\\b).{0,100}")) {
        return true;
      }
    }
    return false;
  }

  private static boolean assignsReplayOwnershipToOrder(String constraints) {
    for (String clause : safe(constraints).split("[.\\n;]+")) {
      String normalized = clause.toLowerCase(Locale.ROOT);
      if (!normalized.contains("order")
          || !containsAny(
              normalized,
              "idempotency_key",
              "scope_hash",
              "response_body",
              "response_status",
              "replay ownership")) {
        continue;
      }
      if (!hasPositiveInstruction(
          normalized,
          "store",
          "persist",
          "own ",
          "owns ",
          "field",
          "column",
          "order:",
          "on order")) {
        continue;
      }
      if (normalized.matches(
              "(?is).*\\border\\b.{0,100}(?:idempotency_key|scope_hash|response_body|response_status|replay"
                  + " ownership).*")
          || normalized.matches(
              "(?is).*(?:idempotency_key|scope_hash|response_body|response_status|replay ownership)"
                  + ".{0,100}\\b(?:on|in|to)\\s+(?:the\\s+)?order\\b.*")) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsNonCanonicalBuildCommand(String description) {
    return description.contains("tests/-v")
        || description.contains("tests/-k")
        || description.contains("tests/--")
        || description.contains("tests/acceptance/--")
        || description.matches("(?s).*tests/acceptance/-[a-z].*")
        || description.contains("npx --prefix frontend vitest run")
        || description.matches("(?s).*if no e2e script exists[^.\\n]{0,100}skip.*")
        || description.matches(
            "(?s).*build\\.sh[^.\\n]{0,240}(?:runs?|executes?|contains?|includes?)"
                + "[^.\\n]{0,160}python (?:-m alembic|-c ).*");
  }

  private static void addQuantityInvariantViolations(
      PlannedTask task,
      Plan plan,
      String taskIdentity,
      String description,
      String constraints,
      List<String> violations) {
    String citedRequirements =
        plan.requirements() == null
            ? ""
            : plan.requirements().stream()
                .filter(java.util.Objects::nonNull)
                .filter(
                    requirement ->
                        task.meetsRequirements() != null
                            && task.meetsRequirements().contains(requirement.id()))
                .map(RequirementSpec::text)
                .filter(java.util.Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining("\n"));
    boolean reservationLedger =
        constraints.contains("reservation accounting leaves inventory.on_hand unchanged");
    if (reservationLedger
        && description.matches(
            "(?s).*(?:increment|decrement)(?:s|ing)?\\s+`?on_hand`?[^.\\n]{0,100}"
                + "(?:increment|decrement)(?:s|ing)?\\s+`?reserved`?.*")) {
      violations.add(
          "Task description mutates on_hand while reserving/releasing despite the reservation"
              + " ledger contract");
    }
    boolean adjustmentMustPreserveReserved =
        citedRequirements.contains("adjustment")
            && citedRequirements.contains("reserved")
            && (citedRequirements.contains("lower than")
                || citedRequirements.contains("below reserved")
                || citedRequirements.contains("available"));
    if (adjustmentMustPreserveReserved
        && containsAny(
            taskIdentity + " " + description, "stock adjustment", "adjust_stock", "adjuststock")
        && description.matches("(?s).*`?on_hand`?\\s*\\+\\s*[^\\n;]{1,100}?\\s*>=\\s*0\\b.*")) {
      violations.add("Stock adjustment guard ignores reserved inventory availability");
    }
    boolean transferMustPreserveAvailability =
        citedRequirements.contains("transfer")
            && (citedRequirements.contains("available")
                || citedRequirements.contains("insufficient"));
    if (transferMustPreserveAvailability
        && containsAny(taskIdentity + " " + description, "stock transfer", "transfer_stock")
        && description.matches("(?s).*`?on_hand`?\\s*-\\s*[^\\n;]{1,100}?\\s*>=\\s*0\\b.*")) {
      violations.add("Stock transfer source guard ignores reserved inventory availability");
    }
  }

  private static boolean rootBuildSectionChangesDirectory(String description) {
    String normalized = safe(description).toLowerCase(Locale.ROOT);
    java.util.regex.Matcher section =
        java.util.regex.Pattern.compile(
                "(?is)(?:^|\\n)\\s*(?:[-*]\\s*)?(?:root\\s+)?(?:\\*\\*|`)?"
                    + "(?:\\./)?build\\.sh"
                    + "(?:`|\\*\\*)?(?:\\s*\\([^)]*\\))?\\s*(?::|—|-)\\s*(.*?)"
                    + "(?=\\n\\s*(?:[-*]\\s*)?(?:\\*\\*|`)?"
                    + "(?:run_tests\\.sh|test\\.sh|readme)|\\z)")
            .matcher(normalized);
    while (section.find()) {
      if (section.group(1).matches("(?s).*\\bcd\\s+backend\\b.*")) return true;
    }
    return normalized.matches(
        "(?s).*\\bbuild\\.sh\\b[^.\\n]{0,180}"
            + "(?:runs?|executes?|contains?|includes?)[^.\\n]{0,300}\\bcd\\s+backend\\b.*");
  }

  private static boolean containsCompleteIdempotencyRecordSchema(String description) {
    String compact = safe(description).replaceAll("[^a-z0-9]", "");
    return compact.contains("idempotencyrecord")
        && compact.contains("key")
        && compact.contains("scope")
        && compact.contains("requestbodyhash")
        && compact.contains("state")
        && compact.contains("responsestatus")
        && compact.contains("responsebody")
        && compact.contains("processing")
        && compact.contains("completed")
        && (compact.contains("uniqueconstraintkeyscope")
            || compact.contains("uniqueconstraintonkeyscope"));
  }

  private static boolean definesIdempotencyRecordSchema(String description) {
    for (String clause : safe(description).split("(?<=[.;\\n])")) {
      String normalized = clause.toLowerCase(Locale.ROOT);
      if (normalized.contains("idempotencyrecord")
          && !containsAny(normalized, "verify ", "assert ", "test ", "expect ")
          && containsAny(
              normalized,
              " fields",
              " columns",
              " model",
              " table",
              " schema",
              "idempotencyrecord`:",
              "idempotencyrecord:")) return true;
    }
    return false;
  }
}
