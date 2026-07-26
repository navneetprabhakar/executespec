package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.orderSchemaDefinesStatus;
import static com.navneet.executespec.agent.TaskExpanderAgent.MAX_DESCRIPTION_CHARS;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;
import static com.navneet.executespec.agent.TaskExpansionSemanticContract.*;
import static com.navneet.executespec.agent.TaskExpansionTextNormalizer.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TaskExpansionPlanContract {
  private TaskExpansionPlanContract() {}

  public static List<String> crossTaskContractViolations(Plan plan) {
    if (plan == null || plan.tasks() == null) return List.of();
    List<String> violations = new ArrayList<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      String evidence = taskContractEvidence(task);
      List<String> taskViolations = new ArrayList<>();
      addIdempotencySchemaCompatibilityViolations(task, plan, evidence, taskViolations);
      addConsumerFieldCompatibilityViolations(task, plan, evidence, taskViolations);
      for (String violation : taskViolations) {
        violations.add(task.id() + ": " + violation);
      }
    }
    return List.copyOf(new java.util.LinkedHashSet<>(violations));
  }

  public static List<String> finalPlanContractViolations(Plan plan) {
    if (plan == null || plan.tasks() == null) return List.of("assembled plan has no tasks");
    List<String> violations = new ArrayList<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null) {
        violations.add("assembled plan contains a null task");
        continue;
      }
      if (safe(task.description()).length() > MAX_DESCRIPTION_CHARS) {
        violations.add(
            task.id()
                + ": description exceeds "
                + MAX_DESCRIPTION_CHARS
                + " characters after coherence");
      }
      if (task.hardConstraints() != null && task.hardConstraints().size() > 12) {
        violations.add(task.id() + ": hard constraints exceed 12 after coherence");
      }
      for (String violation : semanticViolations(task, plan)) {
        violations.add(task.id() + ": " + violation);
      }
    }
    violations.addAll(crossTaskContractViolations(plan));
    for (String qualityViolation : PlanContractQualityGate.violations(plan)) {
      boolean alreadyAttributed =
          violations.stream()
              .anyMatch(
                  existing ->
                      existing.equals(qualityViolation)
                          || existing.endsWith(": " + qualityViolation));
      if (!alreadyAttributed) violations.add(qualityViolation);
    }
    return List.copyOf(new java.util.LinkedHashSet<>(violations));
  }

  public static Plan normalizeFinalPlan(Plan plan) {
    return TaskExpansionPlanNormalizer.normalize(plan);
  }

  static List<String> prioritizeHardConstraints(PlannedTask task) {
    return TaskExpansionPlanNormalizer.prioritizeHardConstraints(task);
  }

  /** Reject split-brain idempotency schemas before worker spend. */
  static void addIdempotencySchemaCompatibilityViolations(
      PlannedTask task, Plan plan, String consumerEvidence, List<String> violations) {
    if (plan.tasks() == null
        || !consumerEvidence.contains("idempotencyrecord")
        || (!consumerEvidence.contains("idempotency_key")
            && !consumerEvidence.contains("scope_hash"))) return;

    PlannedTask candidate = canonicalIdempotencySchemaProducer(plan);
    if (candidate == null || java.util.Objects.equals(candidate.id(), task.id())) return;
    String producerEvidence = taskContractEvidence(candidate);

    String producerCompact = producerEvidence.replaceAll("[^a-z0-9]", "");
    boolean producerUsesKeyAndScope =
        producerCompact.contains("uniqueconstraintkeyscope")
            || producerCompact.contains("uniqueconstraintonkeyscope");
    boolean bothLegacyAliasesSuperseded =
        consumerEvidence.contains("idempotency_key and scope_hash are forbidden")
            || consumerEvidence.contains("do not use idempotency_key or scope_hash");
    boolean keyAliasSuperseded =
        bothLegacyAliasesSuperseded
            || consumerEvidence.contains("idempotency_key is a forbidden alias")
            || consumerEvidence.contains("idempotency_key is forbidden");
    boolean scopeAliasSuperseded =
        bothLegacyAliasesSuperseded
            || consumerEvidence.contains("scope_hash is a forbidden alias")
            || consumerEvidence.contains("scope_hash is forbidden");
    if (producerUsesKeyAndScope
        && usesIdempotencyRecordAlias(consumerEvidence, "idempotency_key")
        && !keyAliasSuperseded) {
      violations.add(
          "IdempotencyRecord consumer invents idempotency_key but schema task "
              + candidate.id()
              + " defines key scoped by scope");
    }
    if (producerEvidence.contains("request_body_hash")
        && usesIdempotencyRecordAlias(consumerEvidence, "scope_hash")
        && !scopeAliasSuperseded) {
      violations.add(
          "IdempotencyRecord consumer invents scope_hash but schema task "
              + candidate.id()
              + " defines request_body_hash");
    }
  }

  /** Returns the plan-owned IdempotencyRecord schema instead of imposing one without evidence. */
  static PlannedTask canonicalIdempotencySchemaProducer(Plan plan) {
    if (plan == null || plan.tasks() == null) return null;
    return plan.tasks().stream()
        .filter(java.util.Objects::nonNull)
        .filter(
            candidate -> {
              String evidence = taskContractEvidence(candidate);
              return evidence.contains("idempotencyrecord")
                  && isPersistenceSchemaProducer(candidate, evidence);
            })
        .findFirst()
        .orElse(null);
  }

  static boolean usesIdempotencyRecordAlias(String evidence, String alias) {
    String normalizedEvidence =
        evidence.replaceAll("(?i)\\bidempotencyrecord\\s*\\.\\s*", "idempotencyrecord ");
    for (String sentence : normalizedEvidence.split("[.!?;\\n]+")) {
      if (!sentence.contains("idempotencyrecord") || !sentence.contains(alias)) continue;
      if (sentence.contains("forbidden")
          || sentence.contains("must not")
          || sentence.contains("do not use")
          || sentence.contains("does not use")
          || sentence.contains("not declare")) continue;
      return true;
    }
    return false;
  }

  static void addGlobalBehaviorFeasibilityViolations(
      PlannedTask task,
      Plan plan,
      String evidence,
      String compact,
      boolean schemaProducer,
      List<String> violations) {
    boolean implementationTask = isImplementationTask(task);
    String executableDescription =
        (safe(task.title())
                + " "
                + safe(task.description())
                + " "
                + String.join(
                    " ", task.hardConstraints() == null ? List.of() : task.hardConstraints())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
            .toLowerCase(Locale.ROOT);
    String allRequirements =
        plan.requirements() == null
            ? ""
            : plan.requirements().stream()
                .filter(java.util.Objects::nonNull)
                .map(RequirementSpec::text)
                .filter(java.util.Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining("\n"));
    boolean exactIdempotentReplay =
        allRequirements.contains("idempotency")
            && (allRequirements.contains("same key")
                || allRequirements.contains("replay")
                || allRequirements.contains("conflicting"));
    boolean orderContractConsumer =
        containsAny(evidence, "post /api/orders", "createorder", "create_order", "ordercreate");
    if (exactIdempotentReplay
        && orderContractConsumer
        && PlanSafetyTextSupport.makesIdempotencyHeaderOptional(evidence)) {
      violations.add("Task description makes the required Idempotency-Key header optional");
    }
    boolean reservationAggregateRequired =
        (allRequirements.contains("reservation lines")
                || allRequirements.contains("reservation line")
                || allRequirements.contains("reservations, reservation lines")
                || (allRequirements.contains("reservation aggregate")
                    && allRequirements.contains("persisted lines")))
            && (allRequirements.contains("reservation lifecycle")
                || allRequirements.contains("release/cancel a reservation")
                || allRequirements.contains("release reservation")
                || allRequirements.contains("cancel reservation"));
    if (reservationAggregateRequired && isReservationSchemaProducer(task)) {
      boolean hasReservationParent =
          compact.contains("reservationmodel")
              || compact.contains("reservationentity")
              || compact.contains("reservationtable")
              || compact.contains("reservationaggregate")
              || compact.contains("classreservation")
              || compact.contains("reservationpersists")
              || evidence.contains("reservation parent")
              || evidence.contains("reservation owns")
              || evidence.matches("(?s).*reservation.{0,60}parent aggregate.*");
      if (!hasReservationParent) {
        violations.add(
            "Plan requires a persisted Reservation aggregate owning ReservationLine children");
      }
    }
    boolean idempotencySchemaOwner =
        schemaProducer && evidence.contains("idempotency") && isIdempotencySchemaOwner(task, plan);
    if (exactIdempotentReplay && idempotencySchemaOwner) {
      if (!compact.contains("idempotencyrecord")) {
        violations.add("Plan requires a separate durable IdempotencyRecord ownership/replay model");
      }
      boolean hasRequestFingerprint =
          compact.contains("requestbodyhash")
              || compact.contains("requesthash")
              || compact.contains("payloadhash");
      if (!hasRequestFingerprint
          || !compact.contains("responsebody")
          || !(compact.contains("responsestatus") || compact.contains("statuscode"))) {
        violations.add("Plan requires durable request hash plus exact response status/body fields");
      }
    }
    boolean idempotencyBehaviorTask =
        implementationTask
            && (executableDescription.contains("idempotency service")
                || executableDescription.contains("idempotency_service")
                || executableDescription.contains("check_or_claim")
                || executableDescription.contains("idempotency middleware")
                || executableDescription.contains("idempotency endpoint")
                || (!schemaProducer && executableDescription.contains("idempotencyrecord")));
    if (exactIdempotentReplay
        && idempotencyBehaviorTask
        && !(executableDescription.contains("before any business mutation")
            && executableDescription.contains("same transaction"))) {
      violations.add("Plan requires idempotency ownership before mutation in the same transaction");
    }
    boolean exactlyOnceConcurrentRelease =
        allRequirements.contains("concurrent")
            && (allRequirements.contains("release") || allRequirements.contains("cancel"))
            && allRequirements.contains("exactly once");
    if (exactlyOnceConcurrentRelease
        && implementationTask
        && (executableDescription.contains("release_reservation")
            || executableDescription.contains("release reservation"))
        && !(executableDescription.contains("where status")
            && executableDescription.contains("rowcount")
            && (executableDescription.contains("conditional update")
                || executableDescription.contains("guarded state")
                || executableDescription.contains("guarded update")))) {
      violations.add(
          "Plan requires release to claim the state transition with a conditional status UPDATE"
              + " and rowcount check before restoring stock");
    }
    boolean sqliteRequired = allRequirements.contains("sqlite");
    if (sqliteRequired
        && schemaProducer
        && (compact.contains("serverdefaultfuncuuid4")
            || compact.contains("serverdefaultuuidgenerate"))) {
      violations.add(
          "SQLite does not provide the proposed server-side UUID function; use a portable"
              + " Python-side UUID default and persist a SQLite-compatible UUID value");
    }
    if (exactIdempotentReplay && idempotencySchemaOwner && evidence.contains("idempotencyrecord")) {
      boolean scopedUnique =
          compact.contains("uniqueconstraintkeyscope")
              || compact.contains("uniqueconstraintonkeyscope")
              || compact.contains("uniqueconstraintidempotencykeyscope")
              || compact.contains("uniqueidempotencykeyscope");
      if (!scopedUnique) {
        violations.add(
            "IdempotencyRecord uniqueness must cover both the key and its mutation scope");
      }
      boolean hasProcessingLifecycle =
          evidence.contains("processing") && evidence.contains("completed");
      boolean responseCanStartEmpty =
          evidence.matches("(?s).*response_(?:status|body).{0,100}(?:nullable|optional|null).*")
              || evidence.matches("(?s).*(?:nullable|optional).{0,100}response_(?:status|body).*");
      if (!hasProcessingLifecycle || !responseCanStartEmpty) {
        violations.add(
            "Concurrent idempotency ownership needs PROCESSING/COMPLETED state and nullable"
                + " response fields until the winning transaction completes");
      }
    }
    boolean splitIdempotencyTransaction =
        PlanSafetyIdempotencySupport.hasSplitTransactionInstruction(executableDescription);
    if (exactIdempotentReplay && idempotencyBehaviorTask && splitIdempotencyTransaction) {
      violations.add(
          "Idempotency ownership, business mutation, and completed replay record must share one"
              + " transaction; a separately committed middleware claim is not atomic");
    }
  }

  /**
   * Deterministic implementation guards must never turn a verification-only task into the owner of
   * the production behavior it is supposed to observe. Generated black-box, integration, browser,
   * and unit-test descriptions naturally mention endpoint and service names; treating those words
   * as implementation ownership made the final expansion fail after every earlier task had already
   * spent tokens.
   */
  static boolean isImplementationTask(PlannedTask task) {
    if (task == null) return false;
    String title = safe(task.title()).toLowerCase(Locale.ROOT);
    List<String> files = task.touchesFiles() == null ? List.of() : task.touchesFiles();
    boolean explicitlyVerificationOnly =
        title.contains("black-box")
            || title.contains("blackbox")
            || title.contains("acceptance test")
            || title.contains("integration test")
            || title.contains("browser e2e")
            || title.contains("playwright");
    boolean allFilesAreVerification =
        !files.isEmpty()
            && files.stream()
                .filter(java.util.Objects::nonNull)
                .map(path -> path.replace('\\', '/').toLowerCase(Locale.ROOT))
                .allMatch(
                    path ->
                        path.matches("(?s).*(?:^|[/_.-])tests?(?:[/_.-]|$).*")
                            || path.contains("/e2e/")
                            || path.endsWith(".spec.ts")
                            || path.endsWith(".spec.tsx"));
    boolean verificationOnly = explicitlyVerificationOnly || allFilesAreVerification;
    return !verificationOnly;
  }

  static boolean isIdempotencySchemaOwner(PlannedTask task, Plan plan) {
    if (task == null || !isImplementationTask(task)) return false;
    String taskEvidence = taskContractEvidence(task);
    String description = safe(task.description()).toLowerCase(Locale.ROOT);
    String identity =
        ((task.title() == null ? "" : task.title())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
            .toLowerCase(Locale.ROOT);
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return true;

    PlannedTask explicitDedicatedOwner =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(TaskExpansionPlanContract::isImplementationTask)
            .filter(
                candidate ->
                    safe(candidate.title()).toLowerCase(Locale.ROOT).contains("idempotency"))
            .filter(
                candidate ->
                    isPersistenceSchemaProducer(candidate, taskContractEvidence(candidate)))
            .findFirst()
            .orElse(null);
    if (explicitDedicatedOwner != null) {
      return java.util.Objects.equals(explicitDedicatedOwner.id(), task.id());
    }

    PlannedTask orderSchemaOwner =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(TaskExpansionPlanContract::isImplementationTask)
            .filter(
                candidate ->
                    !PlanSafetyMigrationSupport.isInfrastructureScaffold(
                        taskContractEvidence(candidate)))
            .filter(
                candidate -> {
                  String candidateEvidence = taskContractEvidence(candidate);
                  if (!isPersistenceSchemaProducer(candidate, candidateEvidence)) return false;
                  String candidateIdentity =
                      (safe(candidate.title())
                              + " "
                              + String.join(
                                  " ",
                                  candidate.touchesFiles() == null
                                      ? List.of()
                                      : candidate.touchesFiles()))
                          .toLowerCase(Locale.ROOT);
                  return candidateIdentity.contains("order")
                      || candidateIdentity.contains("/models/order");
                })
            .findFirst()
            .orElse(null);
    if (orderSchemaOwner != null) return java.util.Objects.equals(orderSchemaOwner.id(), task.id());

    // Without an aggregate migration, preserve an explicit persistence owner before falling back
    // to title heuristics. A router/service task named "idempotent order creation" remains a
    // behavior consumer unless it is the only actual schema-producing task.
    List<PlannedTask> actualOwners =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(TaskExpansionPlanContract::isImplementationTask)
            .filter(
                candidate -> {
                  String candidateEvidence = taskContractEvidence(candidate);
                  return safe(candidate.description())
                          .toLowerCase(Locale.ROOT)
                          .contains("idempotencyrecord")
                      && isPersistenceSchemaProducer(candidate, candidateEvidence);
                })
            .toList();
    if (!actualOwners.isEmpty()) {
      return java.util.Objects.equals(actualOwners.getFirst().id(), task.id());
    }

    PlannedTask dedicatedOwner =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(TaskExpansionPlanContract::isImplementationTask)
            .filter(
                candidate ->
                    ((safe(candidate.title())
                                + " "
                                + String.join(
                                    " ",
                                    candidate.touchesFiles() == null
                                        ? List.of()
                                        : candidate.touchesFiles()))
                            .toLowerCase(Locale.ROOT))
                        .contains("idempotency"))
            .findFirst()
            .orElse(null);
    if (dedicatedOwner != null) return java.util.Objects.equals(dedicatedOwner.id(), task.id());

    PlannedTask firstPersistenceOwner =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(TaskExpansionPlanContract::isImplementationTask)
            .filter(
                candidate ->
                    isPersistenceSchemaProducer(candidate, taskContractEvidence(candidate)))
            .findFirst()
            .orElse(null);
    return firstPersistenceOwner == null
        ? identity.contains("idempotency")
        : java.util.Objects.equals(firstPersistenceOwner.id(), task.id());
  }

  static String taskContractEvidence(PlannedTask task) {
    return ((task.title() == null ? "" : task.title())
            + " "
            + (task.description() == null ? "" : task.description())
            + " "
            + String.join(" ", task.hardConstraints() == null ? List.of() : task.hardConstraints())
            + " "
            + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
        .toLowerCase(Locale.ROOT);
  }

  static boolean isOrderSchemaProducer(PlannedTask task, String evidence) {
    if (task == null || evidence == null) return false;
    String identity =
        ((task.title() == null ? "" : task.title())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
            .toLowerCase(Locale.ROOT);
    String ownedDescription = safe(task.description()).toLowerCase(Locale.ROOT);
    boolean explicitOrderIdentity =
        identity.contains("order model")
            || identity.contains("order persistence")
            || identity.matches("(?s).*(?:^|[/_.-])models?/order(?:[/_.-]|$).*")
            || identity.matches("(?s).*(?:^|[/_.-])order_model(?:[/_.-]|$).*");
    boolean genericPersistenceIdentity =
        identity.contains("database model")
            || identity.contains("domain model")
            || identity.contains("migration")
            || identity.contains("alembic")
            || identity.matches("(?s).*(?:^|[/_.-])entities(?:[/_.-]|$).*");
    boolean explicitlyDefinesOrderSchema =
        ownedDescription.contains("`order`:")
            || ownedDescription.contains("**order**")
            || ownedDescription.matches(
                "(?s).*\\border(?:\\s+(?:table|model|entity|aggregate))?\\s+"
                    + "(?:has|persists|defines|contains|owns)\\b.*");
    return explicitOrderIdentity || (genericPersistenceIdentity && explicitlyDefinesOrderSchema);
  }

  static boolean isReservationSchemaProducer(PlannedTask task) {
    if (task == null) return false;
    String identity =
        ((task.title() == null ? "" : task.title())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
            .replace('\\', '/')
            .toLowerCase(Locale.ROOT);
    String ownedDescription = safe(task.description()).toLowerCase(Locale.ROOT);
    return identity.contains("reservation model")
        || identity.contains("reservation persistence")
        || identity.matches("(?s).*(?:^|[/_.-])models?/reservation(?:[/_.-]|$).*")
        || (containsAny(identity, "migration", "alembic", "model")
            && containsAny(
                ownedDescription,
                "reservationline",
                "reservation line",
                "reservation parent",
                "reservation aggregate"));
  }

  static boolean isPersistenceSchemaProducer(PlannedTask task, String evidence) {
    if (task == null || evidence == null || !isImplementationTask(task)) return false;
    String identity =
        ((task.title() == null ? "" : task.title())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
            .toLowerCase(Locale.ROOT);
    boolean frontendOnly =
        identity.contains("frontend")
            || identity.contains("react")
            || identity.contains("vite")
            || identity.matches("(?s).*(?:^|/)frontend/.*");
    boolean persistenceIdentity =
        identity.contains("data model")
            || identity.contains("database model")
            || identity.matches("(?s).*\\bmodels?\\b.*")
            || identity.contains("migration")
            || identity.contains("alembic")
            || identity.matches("(?s).*(?:^|[/_.-])models?(?:[/_.-]|$).*")
            || identity.matches("(?s).*(?:^|[/_.-])entities(?:[/_.-]|$).*");
    return !frontendOnly && persistenceIdentity;
  }

  static void addConsumerFieldCompatibilityViolations(
      PlannedTask task, Plan plan, String consumerEvidence, List<String> violations) {
    if (isOrderSchemaProducer(task, consumerEvidence)) return;
    boolean consumesOrderCreation =
        containsAny(
            consumerEvidence, "post /api/orders", "createorder", "create_order", "ordercreate");
    boolean consumesOrderStatus =
        consumerEvidence.contains("order.status")
            || consumerEvidence.matches("(?s).*order.{0,80}(?:is in|has|with).{0,80}status.*")
            || consumerEvidence.matches("(?s).*order.{0,50}status.*");
    if ((!consumesOrderStatus && !consumesOrderCreation) || plan.tasks() == null) return;

    List<PlannedTask> producers =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(candidate -> !java.util.Objects.equals(candidate.id(), task.id()))
            .filter(candidate -> isOrderSchemaProducer(candidate, taskContractEvidence(candidate)))
            .toList();
    if (producers.isEmpty()) return;

    PlannedTask canonical =
        producers.stream()
            .filter(candidate -> orderSchemaDefinesStatus(taskContractEvidence(candidate)))
            .findFirst()
            .orElse(producers.getFirst());
    String canonicalEvidence = taskContractEvidence(canonical);
    String schema = orderSchemaSection(canonicalEvidence);
    if (consumesOrderCreation
        && hasPositiveInstruction(consumerEvidence, "customer_name")
        && !schema.contains("customer_name")) {
      violations.add(
          "Frontend order consumer contradicts the canonical backend payload with alternate"
              + " identity fields");
    }
    if (producers.stream()
        .map(TaskExpansionPlanContract::taskContractEvidence)
        .noneMatch(PlanSafetyEvidenceSupport::orderSchemaDefinesStatus)) {
      String producerLabel =
          producers.size() == 1
              ? "task " + producers.getFirst().id()
              : "tasks " + producers.stream().map(PlannedTask::id).toList();
      violations.add(
          "Order consumer requires status but schema "
              + producerLabel
              + (producers.size() == 1 ? " does" : " do")
              + " not define Order.status");
    }
  }

  static String orderSchemaSection(String producerEvidence) {
    int start =
        PlanSafetyEvidenceSupport.firstNonNegative(
            producerEvidence.indexOf("`order`:"),
            producerEvidence.indexOf("order model"),
            producerEvidence.indexOf("**order**"));
    if (start < 0) return "";
    int end =
        PlanSafetyEvidenceSupport.firstPositiveAfter(
            producerEvidence, start, "`reservation`", "reservation.py", "reservation model");
    return producerEvidence.substring(start, end < 0 ? producerEvidence.length() : end);
  }
}
