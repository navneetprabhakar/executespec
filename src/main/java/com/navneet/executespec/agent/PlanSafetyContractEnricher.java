package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.*;
import static com.navneet.executespec.agent.PlanSafetyStructureSupport.*;
import static com.navneet.executespec.agent.PlanSafetyTextSupport.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic safety enrichment for mechanically discoverable planning contracts.
 *
 * <p>The provider-backed coherence pass still owns project-specific schema and interface matching.
 * This class owns invariants that must not disappear because a lightweight model overlooked them.
 */
public final class PlanSafetyContractEnricher {

  static final List<String> ORDER_STATES =
      List.of(
          "DRAFT",
          "PENDING",
          "RESERVED",
          "COMMITTED",
          "CONFIRMED",
          "COMPLETED",
          "CANCELLED",
          "CANCELED",
          "CREATED",
          "FULFILLED",
          "ACTIVE",
          "PROCESSING");
  static final String RESERVATION_LIFECYCLE_CONSTRAINT =
      "Reservation.status lifecycle is exactly ACTIVE and RELEASED. A successful reservation is"
          + " ACTIVE; release/cancel atomically claims ACTIVE→RELEASED before restoring stock,"
          + " and any second release/cancel returns INVALID_STATE.";
  static final String ORDER_LIFECYCLE_SUFFIX =
      "New and released orders are PENDING; successful reservation transitions PENDING→RESERVED;"
          + " UI and tests must use those exact values. Reservation release state belongs to"
          + " Reservation.status, not Order.status.";
  static final String RESERVATION_LIFECYCLE_SHORT =
      "Reservation.status lifecycle is exactly ACTIVE and RELEASED; a successful reservation is"
          + " ACTIVE and release/cancel transitions it to RELEASED.";

  private PlanSafetyContractEnricher() {}

  public record Result(Plan plan, int contractsApplied, List<String> summaries) {}

  public static Result enrich(Plan plan) {
    return enrich(plan, null);
  }

  public static Result enrich(Plan originalPlan, String authoritativeSpec) {
    PlanExecutableContractReconciler.Result executableClosure =
        PlanExecutableContractReconciler.reconcile(originalPlan);
    Plan plan = executableClosure.plan();
    String plannedRequirements =
        plan.requirements() == null
            ? ""
            : plan.requirements().stream()
                .map(RequirementSpec::text)
                .filter(Objects::nonNull)
                .map(text -> text.toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + "\n" + right);
    String requirements =
        plannedRequirements + "\n" + safe(authoritativeSpec).toLowerCase(Locale.ROOT);
    boolean authenticatedPrincipalRequired = positivelyRequiresAuthentication(requirements);
    boolean resourceOwnershipRequired =
        authenticatedPrincipalRequired
            && containsAny(
                requirements,
                "their own ",
                "owned by",
                "resource owner",
                "cross-principal",
                "cross principal",
                "tenant-owned",
                "tenant scoped");
    boolean principalScopedIdempotencyRequired =
        authenticatedPrincipalRequired && requirements.contains("idempotency");
    boolean exactIdempotentReplayRequired =
        requirements.contains("idempotency")
            && (requirements.contains("replay")
                || requirements.contains("same key")
                || requirements.contains("conflicting"));
    boolean migrationsRequired = requirements.contains("migration");
    boolean reservationArithmetic =
        requirements.contains("reservation")
            && (requirements.contains("stock") || requirements.contains("quantity"));
    boolean reservationAggregateRequired =
        (requirements.contains("reservation lines")
                || requirements.contains("reservation line")
                || requirements.contains("reservations, reservation lines")
                || (requirements.contains("reservation aggregate")
                    && requirements.contains("persisted lines")))
            && (requirements.contains("reservation lifecycle")
                || requirements.contains("release/cancel a reservation")
                || requirements.contains("release reservation")
                || requirements.contains("cancel reservation"));
    boolean reservationLedgerRequired =
        requirements.contains("available")
            && requirements.contains("on_hand")
            && requirements.contains("reserved")
            && (requirements.contains("reservation") || requirements.contains("reserve stock"));
    boolean sqliteConcurrencyRequired =
        requirements.contains("sqlite")
            && (requirements.contains("atomic") || requirements.contains("concurrent"));
    boolean sqliteStockConcurrencyRequired =
        sqliteConcurrencyRequired
            && (requirements.contains("stock") || requirements.contains("quantity"));
    boolean sqliteOverlapConcurrencyRequired =
        sqliteConcurrencyRequired
            && requirements.contains("reservation")
            && requirements.contains("overlap");
    boolean invalidCancellationRequired =
        requirements.contains("cancel")
            && (requirements.contains("invalid state")
                || requirements.contains("invalid-state")
                || requirements.contains("exactly once"))
            && (requirements.contains("double cancellation")
                || requirements.contains("second cancel")
                || requirements.contains("exactly once"));
    boolean blackBoxHttpRequired = plan.runtimeSurface() == Plan.RuntimeSurface.HTTP;
    boolean canonicalSanitizedErrorsRequired =
        requirements.contains("sanitized") && requirements.contains("error");
    boolean orderStatusConsumed =
        plan.tasks() != null
            && plan.tasks().stream()
                .filter(Objects::nonNull)
                .anyMatch(
                    task -> {
                      String evidence = taskEvidence(task);
                      return !TaskExpanderAgent.isOrderSchemaProducer(task, evidence)
                          && consumesOrderStatus(evidence);
                    });
    OrderStatusContract orderStatusContract = findOrderStatusContract(plan.tasks(), requirements);
    boolean mixedDatabaseSessionModes = hasMixedDatabaseSessionModes(plan.tasks());
    boolean synchronousDatabaseRequired =
        containsAny(
            requirements,
            "sqlalchemy 2 synchronous",
            "synchronous sqlalchemy",
            "synchronous sessions",
            "sync sqlalchemy");
    boolean realHttpAcceptanceRequired = requiresRealHttpAcceptance(plan.tasks());
    boolean pureBuildAndTestCommandsRequired =
        requirements.contains("do not install dependencies in build/test commands")
            || requirements.contains("must not install dependencies in build/test commands")
            || (containsAny(requirements, "build and test commands", "build/test commands")
                && containsAny(
                    requirements,
                    "must not install",
                    "do not install",
                    "never install",
                    "must not create environments",
                    "do not create environments"))
            || installsDependenciesFromBuildOrTest(plan.tasks());
    boolean rootFullStackBuildRequired =
        containsAny(
                requirements,
                "root build command",
                "root build script",
                "root build/test",
                "root orchestration",
                "root ./build.sh",
                "root `./build.sh`")
            && requirements.contains("backend")
            && requirements.contains("frontend");
    boolean idempotencyHeaderRequired = requirements.contains("idempotency-key");
    boolean auditHistoryRequired = requirements.contains("audit");
    boolean setupDocumentationRequired =
        requirements.contains("exact setup/migration/start commands")
            || (requirements.contains("setup")
                && requirements.contains("migration")
                && requirements.contains("start commands"));
    boolean singleDevelopmentCommandRequired =
        containsAny(
            requirements,
            "one documented command for development startup",
            "single documented command for development startup",
            "one development startup command",
            "single development startup command");
    boolean documentationOwnerAssigned =
        plan.tasks() != null
            && plan.tasks().stream()
                .filter(Objects::nonNull)
                .flatMap(task -> task.touchesFiles().stream())
                .filter(Objects::nonNull)
                .anyMatch(
                    path -> path.equalsIgnoreCase("README.md") || path.endsWith("/README.md"));
    boolean canonicalTransferModelExists =
        plan.tasks() != null
            && plan.tasks().stream()
                .filter(Objects::nonNull)
                .flatMap(task -> task.touchesFiles().stream())
                .filter(Objects::nonNull)
                .anyMatch(path -> path.toLowerCase(Locale.ROOT).endsWith("/models/transfer.py"));
    String buildCommand = canonicalBuildCommand(plan.buildCommand(), requirements);
    if (rootFullStackBuildRequired && !"./build.sh".equals(buildCommand)) {
      buildCommand = "./build.sh";
    }
    String projectType = canonicalProjectType(plan);
    java.util.Map<ApiOperation, String> canonicalApiEndpoints =
        new java.util.LinkedHashMap<>(findCanonicalApiEndpoints(plan.tasks()));
    if (auditHistoryRequired) {
      // Audit history is one product capability, not an accidental projection of whichever
      // mutation endpoints the model happened to invent first.
      canonicalApiEndpoints.put(ApiOperation.AUDIT_HISTORY, "/api/audit");
    }

    if (executableClosure.repairsApplied() == 0
        && !authenticatedPrincipalRequired
        && !migrationsRequired
        && !reservationArithmetic
        && !sqliteConcurrencyRequired
        && !sqliteOverlapConcurrencyRequired
        && !invalidCancellationRequired
        && !blackBoxHttpRequired
        && !canonicalSanitizedErrorsRequired
        && !orderStatusConsumed
        && !exactIdempotentReplayRequired
        && !reservationAggregateRequired
        && !mixedDatabaseSessionModes
        && !synchronousDatabaseRequired
        && !pureBuildAndTestCommandsRequired
        && !idempotencyHeaderRequired
        && !auditHistoryRequired
        && orderStatusContract == null
        && Objects.equals(projectType, plan.projectType())
        && Objects.equals(buildCommand, plan.buildCommand())) {
      return new Result(plan, 0, List.of());
    }

    PlanSafetyPolicy policy =
        new PlanSafetyPolicy(
            plan,
            requirements,
            authenticatedPrincipalRequired,
            resourceOwnershipRequired,
            principalScopedIdempotencyRequired,
            exactIdempotentReplayRequired,
            migrationsRequired,
            reservationArithmetic,
            reservationAggregateRequired,
            reservationLedgerRequired,
            sqliteConcurrencyRequired,
            sqliteStockConcurrencyRequired,
            sqliteOverlapConcurrencyRequired,
            invalidCancellationRequired,
            blackBoxHttpRequired,
            canonicalSanitizedErrorsRequired,
            orderStatusConsumed,
            orderStatusContract,
            mixedDatabaseSessionModes,
            synchronousDatabaseRequired,
            realHttpAcceptanceRequired,
            pureBuildAndTestCommandsRequired,
            rootFullStackBuildRequired,
            idempotencyHeaderRequired,
            setupDocumentationRequired,
            singleDevelopmentCommandRequired,
            new boolean[] {documentationOwnerAssigned},
            canonicalTransferModelExists,
            canonicalApiEndpoints);
    List<PlannedTask> patchedTasks = new ArrayList<>(plan.tasks().size());
    List<String> summaries = new ArrayList<>(executableClosure.summaries());
    int applied = executableClosure.repairsApplied();
    for (PlannedTask task : plan.tasks()) {
      PlanSafetyTaskPatch patch = new PlanSafetyTaskPatch(task, summaries);
      PlanSafetyTaskPhase1.apply(policy, patch);
      PlanSafetyTaskPhase2.apply(policy, patch);
      PlanSafetyTaskPhase3.apply(policy, patch);
      PlanSafetyTaskPhase4.apply(policy, patch);
      patchedTasks.add(patch.toTask());
      applied += patch.applied;
    }

    String backendApiProducerId =
        patchedTasks.stream()
            .filter(task -> !isFrontendOnlyTask(task) && !isTestOnlyTask(task))
            .filter(
                task ->
                    containsAny(
                        taskIdentityEvidence(task),
                        "rest endpoint",
                        "api endpoint",
                        "api route",
                        "/api/",
                        "/routers/",
                        "/controllers/"))
            .map(PlannedTask::id)
            .reduce((first, second) -> second)
            .orElse(null);
    if (backendApiProducerId != null) {
      for (int index = 0; index < patchedTasks.size(); index++) {
        PlannedTask task = patchedTasks.get(index);
        boolean frontendApiConsumer =
            isFrontendOnlyTask(task)
                && !isTestOnlyTask(task)
                && containsAny(
                    safe(task.description()).toLowerCase(Locale.ROOT), "/api/", "fetch(");
        if (frontendApiConsumer && !task.dependsOn().contains(backendApiProducerId)) {
          List<String> dependencies = new ArrayList<>(task.dependsOn());
          dependencies.add(backendApiProducerId);
          patchedTasks.set(index, withDependencies(task, dependencies));
          applied++;
          summaries.add(task.id() + " depends on backend API producer " + backendApiProducerId);
        }
      }
    }

    if (!Objects.equals(buildCommand, plan.buildCommand())) {
      applied++;
      summaries.add("plan build command is a bounded direct compile smoke");
    }
    if (!Objects.equals(projectType, plan.projectType())) {
      applied++;
      summaries.add("plan project type matches the Python backend execution surface");
    }
    if (applied == 0) return new Result(plan, 0, List.of());
    return new Result(
        withTasks(plan, patchedTasks, buildCommand, projectType), applied, List.copyOf(summaries));
  }
}
