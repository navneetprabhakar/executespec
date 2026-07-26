package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;
import static com.navneet.executespec.agent.TaskExpanderAgent.truncate;
import static com.navneet.executespec.agent.TaskExpansionPlanContract.*;
import static com.navneet.executespec.agent.TaskExpansionTextNormalizer.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TaskExpansionSemanticContract {
  private TaskExpansionSemanticContract() {}

  static boolean isSchemaRepairable(IllegalStateException failure) {
    String message = failure == null ? null : failure.getMessage();
    return message != null
        && (message.contains("did not call submitTaskDetail")
            || message.contains("args are not valid JSON")
            || message.contains("exceed schema limits"));
  }

  static String semanticRepairEvidence(PlannedTask task) {
    String constraints =
        task.hardConstraints() == null ? "" : String.join("\n- ", task.hardConstraints());
    return truncate(
        "taskId="
            + task.id()
            + "\ntitle="
            + task.title()
            + "\ndescription=\n"
            + task.description()
            + "\nhardConstraints=\n- "
            + constraints,
        6_000);
  }

  static PlannedTask applyDeterministicSemanticFallback(PlannedTask task, List<String> violations) {
    if (task == null || violations == null || violations.isEmpty()) return task;
    List<String> constraints =
        new ArrayList<>(task.hardConstraints() == null ? List.of() : task.hardConstraints());
    List<String> touchesFiles =
        new ArrayList<>(task.touchesFiles() == null ? List.of() : task.touchesFiles());
    java.util.regex.Pattern explicitCollection =
        java.util.regex.Pattern.compile(
            "^[^:;]+ requires explicit '([a-z][a-z0-9_-]{2,}) (lines|items)' collection"
                + " cardinality$");
    String description = task.description();
    for (String violation : violations) {
      java.util.regex.Matcher matcher = explicitCollection.matcher(violation);
      String constraint = null;
      if (matcher.matches()) {
        String owner = matcher.group(1);
        String memberType = matcher.group(2).startsWith("line") ? "Line" : "Item";
        String aggregate = Character.toUpperCase(owner.charAt(0)) + owner.substring(1);
        constraint =
            aggregate
                + " must own a persisted one-to-many collection of explicit "
                + aggregate
                + memberType
                + " child rows, one row per requested member; never collapse member product/SKU"
                + " and quantity fields onto "
                + aggregate
                + ".";
        String correction =
            "CONTRACT CORRECTION (binding and superseding earlier per-member parent wording): "
                + aggregate
                + " is one parent aggregate that owns a persisted one-to-many collection of "
                + aggregate
                + memberType
                + " child rows. Store member product/SKU and quantity only on the child rows; do"
                + " not create one "
                + aggregate
                + " parent per member.";
        if (description == null || description.isBlank()) {
          description = correction;
        } else if (!description.contains(correction)) {
          description = description + "\n\n" + correction;
        }
      } else if (violation.equals(
          "Plan requires a separate durable IdempotencyRecord ownership/replay model")) {
        description = rewriteOrderReplayOwnership(description);
        constraint =
            "Define and migrate a separate durable IdempotencyRecord model; do not store replay"
                + " ownership only on the business aggregate.";
      } else if (violation.equals(
          "Plan requires durable request hash plus exact response status/body fields")) {
        constraint =
            "IdempotencyRecord must persist key, scope, request_body_hash, response_status, and"
                + " response_body; enforce a database unique constraint on (key, scope), use"
                + " PROCESSING and COMPLETED lifecycle states, and keep response_status and"
                + " response_body nullable until the winning transaction completes, so exact"
                + " replay and conflicting payload detection are executable.";
      } else if (violation.equals(
          "IdempotencyRecord uniqueness must cover both the key and its mutation scope")) {
        description = rewriteIncompleteIdempotencyRecordSchema(description);
        constraint =
            "IdempotencyRecord must enforce a database unique constraint on (key, scope); key-only"
                + " uniqueness is forbidden.";
      } else if (violation.equals(
          "Concurrent idempotency ownership needs PROCESSING/COMPLETED state and nullable response"
              + " fields until the winning transaction completes")) {
        description = rewriteIncompleteIdempotencyRecordSchema(description);
        constraint =
            "IdempotencyRecord must have PROCESSING and COMPLETED lifecycle states, with nullable"
                + " response_status and response_body until the winning transaction completes.";
      } else if (violation.equals(
          "Plan requires idempotency ownership before mutation in the same transaction")) {
        description = addIdempotencyTransactionContract(description);
        constraint =
            "Claim IdempotencyRecord ownership before any business mutation in the same"
                + " transaction; exact same-payload repeats replay the stored status/body and"
                + " conflicting payloads return the stable 409 response.";
      } else if (violation.equals(
          "Plan requires a persisted Reservation aggregate owning ReservationLine children")) {
        constraint =
            "Define and migrate one persisted Reservation parent aggregate per reservation, with"
                + " lifecycle status and a one-to-many collection of ReservationLine child rows;"
                + " release/cancel operates on the Reservation identity, never by treating Order"
                + " or a single line as the reservation.";
        String correction =
            "CONTRACT CORRECTION (binding): Reservation is a persisted parent aggregate with its"
                + " own lifecycle and owns ReservationLine children. Order remains a separate"
                + " aggregate; release/cancel targets Reservation.id.";
        if (description == null || description.isBlank()) {
          description = correction;
        } else if (!description.contains(correction)) {
          description = description + "\n\n" + correction;
        }
      } else if (violation.startsWith(
          "Plan requires release to claim the state transition with a conditional status UPDATE")) {
        description = addReleaseAlgorithmContract(description);
        constraint =
            "Release/cancel must first claim the lifecycle transition with a conditional UPDATE"
                + " WHERE status matches and verify rowcount before restoring stock, so concurrent"
                + " calls restore it exactly once.";
      } else if (violation.equals(
          "Task description bypasses the required migration path with ORM/manual schema"
              + " creation")) {
        description =
            replaceContradictoryClauses(
                description,
                "Apply the configured Alembic migrations; ORM create_all/manual schema creation"
                    + " is forbidden.",
                "create_all",
                "create all",
                "manually create");
      } else if (violation.startsWith(
          "Task description installs dependencies or creates an environment inside a build/test")) {
        description =
            replaceContradictoryClauses(
                description,
                "Build and test commands use already-installed dependencies and never install"
                    + " packages or create an environment.",
                "pip install",
                "npm ci",
                "npm install",
                "create python venv");
      } else if (violation.startsWith(
          "Real-HTTP subprocess tests require a shared temporary file database")) {
        description =
            replaceContradictoryClauses(
                description,
                "Real-HTTP subprocess tests use a shared temporary file-backed SQLite database.",
                "in-memory sqlite");
      } else if (violation.startsWith(
          "Real-HTTP acceptance contradicts the required subprocess boundary")) {
        description = replaceRealHttpContradictions(description);
      } else if (violation.startsWith(
          "Frontend order consumer contradicts the canonical backend payload")) {
        description = rewriteFrontendOrderPayload(description);
        constraints.replaceAll(
            existing ->
                hasPositiveInstruction(existing, "customer_name", "customer name")
                    ? rewriteFrontendOrderPayload(existing)
                    : existing);
        constraints = new ArrayList<>(constraints.stream().distinct().toList());
      } else if (violation.equals(
          "Order producer persists state instead of the canonical status field")) {
        description = rewriteOrderPersistenceField(description);
        constraint =
            "Persist the order lifecycle in the canonical Order.status field; Order.state is"
                + " forbidden.";
      } else if (violation.startsWith(
          "Root verification task contains non-canonical build or test commands")) {
        description = compileRootVerificationTaskDescription();
      } else if (violation.startsWith(
          "Task is migration-runtime-only but still owns or instructs creation of a versioned"
              + " migration")) {
        description =
            PlanSafetyMigrationSupport.normalizeOwnershipDescription(description, false, null);
        touchesFiles.removeIf(PlanSafetyMigrationSupport::isVersionMigrationPath);
      } else if (violation.startsWith("Task scope overreach: ")) {
        java.util.regex.Matcher ownership =
            java.util.regex.Pattern.compile(
                    "^Task scope overreach: (.+) belongs to sibling ([A-Za-z0-9_-]+)"
                        + " \\(([^)]+)\\)$")
                .matcher(violation);
        if (ownership.matches()) {
          String path = ownership.group(1);
          String owner = ownership.group(2);
          String domain = ownership.group(3);
          touchesFiles.removeIf(candidate -> safe(candidate).replace('\\', '/').equals(path));
          String correction =
              "SCOPE OWNERSHIP CORRECTION (binding and superseding earlier placeholder/create"
                  + " instructions): "
                  + owner
                  + " exclusively owns `"
                  + path
                  + "` and the "
                  + domain
                  + " implementation. This task must not create or modify that artifact.";
          if (!safe(description).contains(correction)) {
            description = safe(description).strip() + "\n\n" + correction;
          }
          constraint = correction;
        }
      } else if (violation.startsWith(
          "Versioned migration path is a placeholder rather than an executable revision: ")) {
        String path = violation.substring(violation.indexOf(": ") + 2);
        touchesFiles.removeIf(candidate -> safe(candidate).replace('\\', '/').equals(path));
        constraint =
            "Use a concrete ordered Alembic revision filename owned by this task; placeholder"
                + " revision names such as XXXX are forbidden.";
      } else if (violation.equals(
          "Task description contains empty numbered implementation steps")) {
        description = removeEmptyNumberedSteps(description);
      } else if (violation.startsWith(
          "Repeated cancellation must return an invalid-state response")) {
        description = replaceRepeatedCancellationSuccess(description);
      } else if (violation.startsWith("Root build execution changes working directory")) {
        description = replaceRootBuildWorkingDirectory(description);
      } else if (violation.equals(
          "Task description makes the required Idempotency-Key header optional")) {
        String requiredHeaderContract =
            "Idempotency-Key is required for every scoped mutating reservation request.";
        description =
            replaceContradictoryClauses(description, requiredHeaderContract, "idempotency-key");
        constraints.replaceAll(
            existing ->
                PlanSafetyTextSupport.makesIdempotencyHeaderOptional(existing)
                    ? replaceContradictoryClauses(
                        existing, requiredHeaderContract, "idempotency-key")
                    : existing);
        constraints = new ArrayList<>(constraints.stream().distinct().toList());
      } else if (violation.startsWith(
          "SQLite does not provide the proposed server-side UUID function")) {
        description = rewriteSqliteUuidDefaults(description);
        constraint =
            "For SQLite UUID identifiers, import Python's uuid module and use a Python-side"
                + " default such as default=lambda: str(uuid.uuid4()); never use"
                + " an unavailable database-side UUID generator.";
      } else if (violation.equals(
          "Task description defines IdempotencyRecord fields outside the canonical key/scope"
              + " contract")) {
        description =
            normalizeIdempotencyRecordAliases(
                replaceContradictoryClauses(
                    description,
                    "IdempotencyRecord uses only key, scope, request_body_hash, state,"
                        + " response_status, and response_body; key_hash, idempotency_key, and"
                        + " scope_hash are forbidden aliases.",
                    "key_hash",
                    "idempotency_key",
                    "scope_hash"));
      } else if (violation.equals(
          "Task description defines an incomplete canonical IdempotencyRecord schema")) {
        description = rewriteIncompleteIdempotencyRecordSchema(description);
      } else if (violation.equals(
          "IdempotencyRecord state machine uses PENDING outside PROCESSING/COMPLETED")) {
        description =
            PlanSafetyReservationTextSupport.normalizeIdempotencyReplayState(
                description.replaceAll("(?i)state\\s*={1,2}\\s*`?PENDING`?", "state==COMPLETED"));
      } else if (violation.equals(
          "IdempotencyRecord state machine uses a business state outside"
              + " PROCESSING/COMPLETED")) {
        description =
            description
                .replaceAll(
                    "(?i)(updates?\\s+(?:the\\s+)?(?:record|idempotency\\s*record)\\s+to\\s+)"
                        + "(?:DRAFT|RESERVED|COMMITTED|CANCELLED)\\b",
                    "$1COMPLETED")
                .replaceAll(
                    "(?i)(response_(?:status|body)[^.\\n]{0,50}\\bwhen\\s+)"
                        + "(?:DRAFT|RESERVED|COMMITTED|CANCELLED)\\b",
                    "$1COMPLETED");
      } else if (violation.equals(
          "Browser E2E task replaces required UI workflow with direct API operations")) {
        description =
            replaceContradictoryClauses(
                description,
                "BROWSER JOURNEY CONTRACT (binding): create prerequisite business data, execute"
                    + " every tested workflow, and verify business outcomes through Playwright"
                    + " page interactions against the real rendered UI. Direct API calls are"
                    + " limited to health/readiness checks.",
                "setup via api",
                "direct api calls in setup",
                "use httpx",
                "use axios",
                "use requests",
                "verify via api");
      } else if (violation.startsWith(
          "Task description mutates on_hand while reserving/releasing")) {
        description =
            replaceContradictoryClauses(
                description,
                "Reservation accounting leaves Inventory.on_hand unchanged: reserve increments"
                    + " reserved only and release decrements reserved only.",
                "on_hand");
      } else if (violation.equals(
          "Stock adjustment guard ignores reserved inventory availability")) {
        description = rewriteStockAdjustmentAvailabilityGuard(description);
      } else if (violation.equals(
          "Stock transfer source guard ignores reserved inventory availability")) {
        description = rewriteStockTransferAvailabilityGuard(description);
      } else if (violation.startsWith("one duplicate GET route ")) {
        description = TaskExpansionRouteContract.reconcileAmbiguousDuplicateReadRoutes(description);
      }
      if (constraint == null) continue;
      if (!constraints.contains(constraint)) constraints.add(constraint);
    }
    if (constraints.equals(task.hardConstraints())
        && touchesFiles.equals(task.touchesFiles())
        && java.util.Objects.equals(description, task.description())) return task;
    return PlannedTask.builder()
        .id(task.id())
        .title(task.title())
        .description(description)
        .complexity(task.complexity())
        .touchesFiles(List.copyOf(touchesFiles))
        .dependsOn(task.dependsOn())
        .hardConstraints(List.copyOf(constraints))
        .meetsRequirements(task.meetsRequirements())
        .taskType(task.taskType())
        .build();
  }

  private static String rewriteSqliteUuidDefaults(String description) {
    String normalized =
        safe(description)
            .replaceAll(
                "(?i)server_default\\s*=\\s*func\\.(?:uuid4|uuid_generate_v4)\\(\\)",
                "default=lambda: str(uuid.uuid4())")
            .replaceAll(
                "(?i)server_default\\s*=\\s*(?:text\\()?['\"]?(?:gen_random_uuid|"
                    + "uuid_generate_v4)\\(\\)['\"]?\\)?",
                "default=lambda: str(uuid.uuid4())");
    String correction =
        "SQLite UUID CONTRACT (binding): import Python's uuid module and generate identifiers"
            + " with default=lambda: str(uuid.uuid4()); persist them in a SQLite-compatible text"
            + " column and do not use a server-side UUID function.";
    if (normalized.contains("default=lambda: str(uuid.uuid4())")
        && !normalized.contains(correction)) {
      normalized = normalized + "\n\n" + correction;
    }
    return normalized;
  }

  private static String rewriteOrderReplayOwnership(String description) {
    String normalized =
        safe(description)
            .replaceAll("(?im)^\\s*-\\s*`?idempotency_key`?\\s*:[^\\n]*(?:\\R|$)", "")
            .replaceAll(
                "(?i)\\s*-\\s*`?idempotency_key`?\\s*:.{0,300}?"
                    + "(?=\\s+-\\s+`?[a-z_]+`?\\s*:|\\s+\\d+\\.\\s+`?|$)",
                "")
            .replaceAll(
                "(?i)store the idempotency key and principal on the Order row",
                "claim the separate IdempotencyRecord before creating the Order");
    String correction =
        "IDEMPOTENCY PERSISTENCE CONTRACT (binding): Order contains business state only. Define"
            + " and migrate a separate IdempotencyRecord with key, scope, request_body_hash,"
            + " PROCESSING/COMPLETED state, nullable response_status, and nullable response_body;"
            + " enforce UniqueConstraint(key, scope).";
    return normalized.contains(correction) ? normalized : normalized + "\n\n" + correction;
  }

  private static String rewriteOrderPersistenceField(String description) {
    return PlanSafetyOrderSupport.normalizeCanonicalStatusReference(description, "Order");
  }

  static String replaceContradictoryClauses(
      String description, String canonicalCorrection, String... forbiddenPositivePhrases) {
    List<String> retained = new ArrayList<>();
    for (String clause : safe(description).split("(?<=[;\\n])|(?<=\\.)(?=\\s+[A-Z])")) {
      String normalized = clause.toLowerCase(Locale.ROOT);
      boolean conflicting =
          containsAny(normalized, forbiddenPositivePhrases)
              && hasPositiveInstruction(normalized, forbiddenPositivePhrases);
      if (!conflicting && !clause.isBlank()) retained.add(clause.trim());
    }
    String base = String.join(" ", retained).trim();
    if (base.contains(canonicalCorrection)) return base;
    return base.isBlank() ? canonicalCorrection : base + "\n\n" + canonicalCorrection;
  }

  private static String normalizeIdempotencyRecordAliases(String description) {
    List<String> clauses = new ArrayList<>();
    for (String clause : safe(description).split("(?<=[.!?;\\n])")) {
      if (clause.toLowerCase(Locale.ROOT).contains("idempotencyrecord")) {
        String forbiddenAliasesMarker = "__CANONICAL_IDEMPOTENCY_FORBIDDEN_ALIASES__";
        clause =
            clause.replaceAll(
                "(?i)idempotency_key\\s+and\\s+scope_hash\\s+are\\s+forbidden\\s+aliases",
                forbiddenAliasesMarker);
        clause =
            clause
                .replaceAll("(?i)\\bkey_hash\\b", "key")
                .replaceAll("(?i)\\bidempotency_key\\b", "key")
                .replaceAll("(?i)\\bscope_hash\\b", "scope")
                .replace(
                    forbiddenAliasesMarker, "idempotency_key and scope_hash are forbidden aliases");
      }
      clauses.add(clause);
    }
    return String.join("", clauses);
  }

  static List<String> semanticViolations(PlannedTask task, Plan plan) {
    if (task == null || plan == null) return List.of();
    java.util.Set<String> cited =
        task.meetsRequirements() == null
            ? java.util.Set.of()
            : java.util.Set.copyOf(task.meetsRequirements());
    String evidence =
        ((task.title() == null ? "" : task.title())
                + " "
                + (task.description() == null ? "" : task.description())
                + " "
                + String.join(
                    " ", task.hardConstraints() == null ? List.of() : task.hardConstraints()))
            .toLowerCase(Locale.ROOT);
    String compact = evidence.replaceAll("[^a-z0-9]", "");
    boolean schemaProducer = isPersistenceSchemaProducer(task, evidence);
    List<String> violations = new ArrayList<>();
    java.util.regex.Pattern collectionPattern =
        java.util.regex.Pattern.compile("\\b([a-z][a-z0-9_-]{2,})\\s+(lines|items)\\b");
    for (RequirementSpec requirement :
        plan.requirements() == null ? List.<RequirementSpec>of() : plan.requirements()) {
      if (requirement == null
          || requirement.id() == null
          || !cited.contains(requirement.id())
          || requirement.text() == null) continue;
      String required = requirement.text().toLowerCase(Locale.ROOT);
      if (schemaProducer) {
        java.util.regex.Matcher matcher = collectionPattern.matcher(required);
        while (matcher.find()) {
          String owner = matcher.group(1).replaceAll("[^a-z0-9]", "");
          // "line items" names collection members, not an owning aggregate. Requiring a literal
          // LineItem model rejects valid OrderLine/CartItem schemas. Owner-bearing phrases such as
          // "reservation lines" and "order items" remain enforceable.
          if (java.util.Set.of(
                  "line", "duplicate", "multiple", "sku", "product", "stock", "warehouse")
              .contains(owner)) continue;
          String member = matcher.group(2).startsWith("line") ? "line" : "item";
          boolean explicitOwnedCollection =
              compact.contains(owner + "collection")
                  || compact.contains(owner + "items")
                  || evidence.matches(
                      "(?s).*"
                          + java.util.regex.Pattern.quote(owner)
                          + ".{0,220}(?:one-to-many|collection|list"
                          + " of|owns).{0,120}(?:line|item).*");
          if (evidence.contains(owner)
              && !compact.contains(owner + member)
              && !compact.contains(owner + member + "s")
              && !explicitOwnedCollection) {
            violations.add(
                requirement.id()
                    + " requires explicit '"
                    + matcher.group()
                    + "' collection cardinality");
          }
        }
      }
      if (required.contains("guarded write")
          && evidence.matches("(?s).*without.{0,180}(conditional|guarded)\\s+update.*")) {
        violations.add(
            requirement.id() + " requires guarded writes but the task description negates them");
      }
    }
    addTaskScopeOwnershipViolations(task, plan, violations);
    addGlobalBehaviorFeasibilityViolations(
        task, plan, evidence, compact, schemaProducer, violations);
    TaskExpansionContradictionValidator.addViolations(task, plan, violations);
    return List.copyOf(new java.util.LinkedHashSet<>(violations));
  }

  private static void addTaskScopeOwnershipViolations(
      PlannedTask task, Plan plan, List<String> violations) {
    if (task.touchesFiles() == null || task.touchesFiles().isEmpty()) return;
    String ownTitle = safe(task.title()).toLowerCase(Locale.ROOT);
    for (String rawPath : task.touchesFiles()) {
      String path = safe(rawPath).replace('\\', '/');
      String lower = path.toLowerCase(Locale.ROOT);
      if (PlanSafetyMigrationSupport.isVersionMigrationPath(path)
          && lower.matches(".*(?:^|[/_.-])(?:xxxx|todo|tbd)(?:[/_.-]).*")) {
        violations.add(
            "Versioned migration path is a placeholder rather than an executable revision: "
                + path);
      }
      String domain = scopedArtifactDomain(lower);
      if (domain == null || titleOwnsDomain(ownTitle, domain)) continue;
      if (lower.contains("/models/")
          && PlanScopeOwnershipReconciler.isResolvedModelOwner(plan, task, path)) continue;
      PlannedTask sibling =
          plan.tasks().stream()
              .filter(java.util.Objects::nonNull)
              .filter(candidate -> !java.util.Objects.equals(candidate.id(), task.id()))
              .filter(
                  candidate ->
                      lower.contains("/pages/")
                          ? PlanSafetyEvidenceSupport.isFrontendOnlyTask(candidate)
                          : !PlanSafetyEvidenceSupport.isFrontendOnlyTask(candidate)
                              && TaskExpansionPlanContract.isImplementationTask(candidate))
              .filter(
                  candidate ->
                      titleOwnsDomain(safe(candidate.title()).toLowerCase(Locale.ROOT), domain))
              .findFirst()
              .orElse(null);
      if (sibling != null) {
        violations.add(
            "Task scope overreach: "
                + path
                + " belongs to sibling "
                + sibling.id()
                + " ("
                + domain
                + ")");
      }
    }
  }

  private static String scopedArtifactDomain(String path) {
    java.util.regex.Matcher model =
        java.util.regex.Pattern.compile(".*/models/([a-z0-9_]+)\\.py$").matcher(path);
    if (model.matches()) {
      String value = model.group(1);
      return java.util.Set.of("__init__", "base").contains(value) ? null : value;
    }
    java.util.regex.Matcher page =
        java.util.regex.Pattern.compile(".*/pages/([a-z0-9_-]+)\\.tsx?$").matcher(path);
    if (page.matches()) {
      String value = page.group(1);
      return java.util.Set.of("notfound", "not_found", "index").contains(value) ? null : value;
    }
    return null;
  }

  private static boolean titleOwnsDomain(String title, String domain) {
    for (String token : domain.split("[_-]+")) {
      String singular =
          token.endsWith("ies")
              ? token.substring(0, token.length() - 3) + "y"
              : token.endsWith("s") && token.length() > 3
                  ? token.substring(0, token.length() - 1)
                  : token;
      if (singular.length() > 2
          && title.matches("(?s).*\\b" + java.util.regex.Pattern.quote(singular) + "s?\\b.*")) {
        return true;
      }
    }
    return false;
  }

  static boolean hasPositiveInstruction(String text, String... needles) {
    for (String clause : safe(text).split("[.\\n;]+")) {
      if (!containsAny(clause, needles)) continue;
      if (containsAny(
          clause,
          "do not",
          "must not",
          "never ",
          "without ",
          "forbidden",
          "prohibit",
          "cannot ",
          "can't ")) continue;
      return true;
    }
    return false;
  }

  static boolean containsAny(String value, String... needles) {
    if (value == null) return false;
    for (String needle : needles) {
      if (needle != null && value.contains(needle)) return true;
    }
    return false;
  }

  /**
   * Validate assembled producer/consumer contracts after every task has been expanded. These checks
   * deliberately do not run inside {@link #semanticViolations(PlannedTask, Plan)}: a consumer task
   * cannot repair an already-expanded producer, so failing it early would prevent the coherence
   * stage from fixing the task that actually owns the schema.
   */
}
