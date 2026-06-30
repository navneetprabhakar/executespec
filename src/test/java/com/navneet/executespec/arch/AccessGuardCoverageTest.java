package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * P4.1 (GA-readiness, 2026-05-06) — pins the multi-tenant access-guard contract on every Spring
 * REST controller method whose URL path contains a run / project / execution id segment.
 *
 * <p>Audit Risk #5 (catastrophic): {@link com.navneet.executespec.auth.security.RunAccessGuard}
 * works correctly when called, but every controller has to remember to call it. A single missing
 * call = tenant data leak. This test makes the contract enforced at build time.
 *
 * <p>Rule: every controller method annotated with {@code @GetMapping / @PostMapping / @PutMapping
 * / @DeleteMapping / @PatchMapping} whose containing class has a {@code @RequestMapping} (or
 * {@code @*Mapping} on the method) path containing one of the protected segments must EITHER:
 *
 * <ul>
 *   <li>call {@link com.navneet.executespec.auth.security.RunAccessGuard} or {@link
 *       com.navneet.executespec.project.security.ProjectAccessGuard} directly, OR
 *   <li>be allowlisted in {@link #ALLOWLIST} below with a one-line justification (admin-only,
 *       public-by-design, etc.).
 * </ul>
 *
 * <p>The test does NOT recurse the call graph — a controller that "delegates to a service that
 * calls the guard" still has to call the guard directly OR be allowlisted. Direct call sites are
 * easier to audit + harder to accidentally remove during a refactor.
 */
class AccessGuardCoverageTest {

  /**
   * Owners of the canonical guard contract — the public path the test prefers controllers route
   * through.
   */
  private static final Set<String> GUARD_FQNS =
      Set.of(
          "com.navneet.executespec.auth.security.RunAccessGuard",
          "com.navneet.executespec.project.security.ProjectAccessGuard");

  /**
   * Regex matched against the simple name of every method called from the controller body.
   *
   * <p>Counts as a tenant access check when the controller calls anything matching:
   *
   * <ul>
   *   <li>{@code requireXxx} / {@code requiresXxx} (e.g. requireRunAccess, requireFeatureEnabled,
   *       requireProjectAccess) — the canonical "this throws 403/404 on miss" verb.
   *   <li>{@code assertXxx} (e.g. assertCanRead, assertAccess, assertCanCreateProject) —
   *       alternative verb for the same throws-on-miss contract.
   *   <li>{@code ensureXxx} (e.g. ensureMember, ensureCallerIsOrgAdmin) — third verb.
   *   <li>{@code loadForXxx} / {@code loadXxxForRead} / {@code loadXxxForWrite} — the pattern used
   *       by repository-style guards that load + check ownership in one call.
   *   <li>{@code checkAccess} / {@code checkPermission} — explicit permission-check verbs.
   *   <li>{@code authorize} (Spring Security pattern).
   * </ul>
   *
   * <p>Any of these called from inside the handler body counts. This is the pragmatic baseline: the
   * existence of an access-named method call IS the signal that the controller author thought about
   * tenant access at all. Consolidating onto the canonical {@link
   * com.navneet.executespec.auth.security.RunAccessGuard} / {@link
   * com.navneet.executespec.project.security.ProjectAccessGuard} is a separate cleanup, tracked as
   * P4.1-followup in the GA-readiness plan.
   */
  private static final java.util.regex.Pattern GUARD_METHOD_NAME_PATTERN =
      java.util.regex.Pattern.compile(
          "^(require|requires|assert|ensure|check|authorize|loadFor|load[A-Z][a-zA-Z]*For)"
              + "[A-Z][a-zA-Z]*$");

  /**
   * Controller-LOCAL helper names that happen to match {@link #GUARD_METHOD_NAME_PATTERN} but are
   * NOT real tenant guards — they only assert the request is authenticated and/or that a repo is
   * linked to a project, never that the <em>caller</em> belongs to the project's org. Counting them
   * as guards lets a cross-org IDOR pass the build (the contracts-controllers leak,
   * security-review-2026-05-25): a stranger in an unrelated org sails past {@code requireAuth} +
   * {@code ensureLinked} and reads/mutates another org's contracts.
   *
   * <p>So a call to one of these names does NOT satisfy the guard requirement. The only ways to
   * satisfy it are (a) a call to a method ON one of {@link #GUARD_FQNS} (the canonical {@code
   * RunAccessGuard} / {@code ProjectAccessGuard}, e.g. {@code assertCanRead} / {@code
   * assertCanWrite}), or (b) a non-excluded name-pattern match (e.g. a delegated {@code
   * requireProjectAccess} service guard). Keep this list to genuinely local, non-tenant helpers
   * only — do NOT add a real guard's method name here.
   */
  private static final Set<String> EXCLUDED_LOCAL_HELPERS =
      Set.of("requireAuth", "ensureLinked", "ensureLinkedAndAccessible", "checkContract");

  /**
   * Path-segment markers — if the controller's path or method's path contains any of these, the
   * method is in scope for the guard requirement.
   */
  private static final Set<String> PROTECTED_SEGMENTS =
      Set.of(
          "/runs/{",
          "/projects/{",
          "/executions/{",
          "/launches/{",
          "/repos/{",
          // C2 (audit 2026-05-29) — the readymade/migration libraries take the tenant projectId
          // in the body/param (not a path id) and reference the recipe by a public {slug}. Scoping
          // their {slug} surfaces forces a guard on resolve / instantiate / dry-run / apply, the
          // cross-org-IDOR cluster the path-id-only matcher previously missed.
          "/library/{",
          "/migrations/{",
          // RL-2026-05-052 (design §10 defect #5) — pin the CLI/Plugin chat surface so any future
          // /api/spec endpoint that binds a run/project id is held to the same guard-coverage bar.
          // The two current methods (chat / getAudit) bind no path id and are allowlisted below.
          "/spec/");

  /**
   * Allowlisted methods (FQCN#methodName) — controllers that legitimately don't need a tenant
   * guard. Each entry MUST have a justification in the comment above it.
   *
   * <p>When you add a controller to this set, you are asserting that one of these conditions is
   * true:
   *
   * <ul>
   *   <li>The endpoint is public-by-design (token-bearer or webhook signature is the auth).
   *   <li>The endpoint is gated by a SUPER_ADMIN role check in {@code SecurityConfig} and the admin
   *       role is the trust boundary.
   *   <li>The endpoint reads only resources the caller already owns (no path-id bind).
   * </ul>
   */
  private static final Set<String> ALLOWLIST =
      new LinkedHashSet<>(
          Set.of(
              // Public-by-design — token in URL is the bearer; SecurityConfig whitelists
              // /api/shared/** + /api/invites/preview. ProjectSharePublicController is the
              // single-purpose public reader for share-link surfaces.
              "com.navneet.executespec.sharing.controller.ProjectSharePublicController#getMeta",
              "com.navneet.executespec.sharing.controller.ProjectSharePublicController#getRun",
              "com.navneet.executespec.sharing.controller.ProjectSharePublicController#getValidationReport",
              "com.navneet.executespec.sharing.controller.ProjectSharePublicController#getCodeReview",
              "com.navneet.executespec.sharing.controller.ProjectSharePublicController#getPrReview",
              "com.navneet.executespec.sharing.controller.ProjectSharePublicController#getArchitecture",
              // SUPER_ADMIN-gated by SecurityConfig URL pattern `/api/admin/**` (line 222
              // requires ROLE_SUPER_ADMIN). The role check is the trust boundary; no non-admin
              // request reaches these handlers. Tenant-bound run/project IDs in their paths
              // are administrative bypass paths used to recover specific runs / projects.
              "com.navneet.executespec.auth.controller.SuperAdminController#forceFailTask",
              "com.navneet.executespec.auth.controller.SuperAdminController#reReviewRun",
              "com.navneet.executespec.folder.ExecutespecMigrationController#migrate",
              // V2 canary surface — runIds in `/api/v2/runs/` reference Run rows which are a
              // separate storage from the production `pipeline_run` table; they're scoped to
              // V2 bring-up testing, not customer data. Live V2 runs go through the production
              // `/api/runs` surface which has its own RunAccessGuard coverage.
              "com.navneet.executespec.rollout.RolloutRunController#poll",
              // Public solution/recipe reads keyed by a non-tenant {slug} — no project / run id
              // bind; tier-gating is per-row in the response body. C2 — audit 2026-05-29.
              "com.navneet.executespec.readymade.controller.ReadymadeController#detail",
              "com.navneet.executespec.migration.controller.MigrationController#detail",
              // RL-2026-05-052 (design §10 defect #5) — CLI/Plugin chat surface. `chat` is a
              // PAT/JWT-authenticated SSE endpoint that binds no run/project id from the URL; the
              // per-run WRITE authz for the governance actions (approve/reject/cancel) is enforced
              // inside ProjectChatActionTools via requireRunWriteAccess. `getAudit` reads a
              // per-user audit row gated by an inline caller-userId equality check (no tenant-id
              // bind). They are in scope only because the surface is now pinned above.
              "com.navneet.executespec.specchat.SpecChatController#chat",
              "com.navneet.executespec.specchat.SpecChatController#getAudit"));

  /**
   * <b>Known legacy drift</b> — empty as of 2026-05-06. The original 37-entry baseline (audit Risk
   * #5, catastrophic) was migrated in two batches:
   *
   * <ul>
   *   <li>Batch 1 (P5.4 commit): TestResultController × 2.
   *   <li>Batch 2: PullRequest × 2 + ProjectExport × 1 + ProjectController × 6 + ActivityFeed × 1 +
   *       Adr × 2 + Invariant × 2 + CodeReview × 2 + PrReview × 1 + StandardsCompliance × 1 +
   *       ProjectRepo project-scoped × 4 + ProjectDocument × 3 + SpecTemplate × 3 = 25 sites.
   *   <li>Batch 3 (this branch's tail): admin endpoints recategorised to ALLOWLIST with
   *       justification, V2 canary endpoint same. ProjectRepo repo-scoped × 3 migrated via a public
   *       {@code service.loadRepoForRead} chokepoint exposed for the controller.
   * </ul>
   *
   * <p>Total: 35 of 35 legacy sites cleared. From here the test is purely forward-protective: a NEW
   * endpoint added without a guard will fail the build, surfacing a drift before the tenant-leak
   * ships.
   *
   * <p><b>Rule:</b> entries can ONLY be added to this set when there's a clear migration plan +
   * ticket; the default response to a failure should be "fix the missing guard" (preferred) or "add
   * to ALLOWLIST with explicit non-tenant-bound justification" (rare).
   */
  private static final Set<String> KNOWN_LEGACY_DRIFT = new LinkedHashSet<>(Set.of());

  /**
   * RL-2026-05-040 — write-annotated handlers
   * ({@code @PostMapping/@PutMapping/@DeleteMapping/ @PatchMapping}) on a protected path that
   * legitimately need only READ access: a mutating HTTP verb used for a read-only computation that
   * mutates no tenant-owned state (e.g. a POST that returns a heuristic estimate or a drift
   * report). Each entry asserts "a cross-org read-only member may invoke this without harm."
   * Default response to a new failure is "swap assertCanRead → assertCanWrite," NOT adding here —
   * keep this list to genuine read-only POSTs only.
   */
  private static final Set<String> READ_ONLY_WRITE_HANDLERS =
      new LinkedHashSet<>(
          Set.of(
              "com.navneet.executespec.contracts.drift.ContractDriftController#checkContract",
              "com.navneet.executespec.contracts.drift.ContractDriftController#checkProject",
              "com.navneet.executespec.contracts.drift.ContractDriftController#checkRepo",
              "com.navneet.executespec.export.ProjectExportController#export",
              // Read-only POST computations gated by assertCanRead (mutate no tenant state):
              // resolve picks a variant; dry-run computes applicability + cost + sample diff.
              // C2 — audit 2026-05-29.
              "com.navneet.executespec.readymade.controller.ReadymadeController#resolve",
              "com.navneet.executespec.migration.controller.MigrationController#dryRun",
              // Fork intentionally reads the source run and creates a separate run owned by the
              // caller; it does not mutate the source run's lifecycle, workspace, gate, or PR
              // state.
              "com.navneet.executespec.api.controller.RunController#forkRun"));

  @Test
  void everyRunOrProjectScopedEndpointCallsAGuard() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.navneet.executespec");

    Set<String> violations = new TreeSet<>();
    Set<String> writeGuardViolations = new TreeSet<>();

    for (var cls : classes) {
      // Only scan @RestController / @Controller classes (anything that responds to web).
      boolean isController =
          cls.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")
              || cls.isAnnotatedWith("org.springframework.stereotype.Controller");
      if (!isController) continue;

      String classBasePath = extractClassBasePath(cls);

      for (JavaMethod m : cls.getMethods()) {
        if (!isHandlerMethod(m)) continue;

        String methodPath = extractMethodPath(m);
        String fullPath = (classBasePath + methodPath);
        if (!touchesProtectedSegment(fullPath)) continue;

        String key = cls.getName() + "#" + m.getName();
        if (ALLOWLIST.contains(key)) continue;
        if (KNOWN_LEGACY_DRIFT.contains(key)) continue;

        boolean callsGuard = callsTenantGuard(m);

        if (!callsGuard) {
          violations.add(key + "  (path=" + fullPath + ")");
        }

        // RL-2026-05-040 — write-shaped handler must not gate a mutation behind a READ guard.
        if (isWriteHandler(m)
            && gatesWriteBehindReadGuard(m)
            && !READ_ONLY_WRITE_HANDLERS.contains(key)) {
          writeGuardViolations.add(key + "  (path=" + fullPath + ")");
        }
      }
    }

    String msg =
        "Multi-tenant access-guard coverage drift detected — NEW endpoint without a guard.\n\n"
            + "The following controller methods bind a run/project/execution id from the URL\n"
            + "but DO NOT call any tenant access guard. This is a tenant-data-leak risk; the\n"
            + "audit (Risk #5, catastrophic) explicitly required catching this at build time.\n\n"
            + "Either:\n"
            + "  1. Add a guard call (preferred — visible at the call site, e.g.\n"
            + "     RunAccessGuard.requireRunAccess(...) or\n"
            + "     ProjectAccessGuard.assertCanRead(...)), OR\n"
            + "  2. Add to AccessGuardCoverageTest.ALLOWLIST with a one-line justification\n"
            + "     (only valid reasons: public-by-design, SUPER_ADMIN-gated, or"
            + " no-tenant-bind).\n\n"
            + "Do NOT extend KNOWN_LEGACY_DRIFT — that set is closed; new entries indicate a\n"
            + "regression. Migrating the legacy set is tracked under P4.1-followup of the\n"
            + "GA-readiness plan.\n\n"
            + "Violations:\n"
            + "  - "
            + String.join("\n  - ", violations);

    assertThat(violations).withFailMessage(msg).isEmpty();

    String writeMsg =
        "Write handler gated behind a READ-only guard — cross-org read-only members can mutate.\n\n"
            + "These mutating endpoints (POST/PUT/DELETE/PATCH) call\n"
            + "ProjectAccessGuard.assertCanRead without assertCanWrite, or\n"
            + "RunAccessGuard.requireRunAccess without requireRunWriteAccess/assertWriteAccess.\n"
            + "Read guards admit users who can view a run/project but must not mutate it"
            + " (RL-2026-05-040).\n\n"
            + "Fix: swap the read guard to the matching write guard. Only if the endpoint genuinely"
            + " needs read\n"
            + "(a POST that triggers a read-only computation + mutates no tenant state) add it to\n"
            + "READ_ONLY_WRITE_HANDLERS with a one-line justification.\n\n"
            + "Violations:\n"
            + "  - "
            + String.join("\n  - ", writeGuardViolations);
    assertThat(writeGuardViolations).withFailMessage(writeMsg).isEmpty();

    // Drift detector — also flag entries in KNOWN_LEGACY_DRIFT that are no longer real
    // violations (controller migrated, method removed, etc.). Keeps the legacy set honest.
    Set<String> staleLegacyEntries = new TreeSet<>(KNOWN_LEGACY_DRIFT);
    staleLegacyEntries.removeAll(scanCurrentViolations());
    assertThat(staleLegacyEntries)
        .withFailMessage(
            "KNOWN_LEGACY_DRIFT contains entries that are no longer real violations.\n"
                + "Either the controller now calls a guard (great — drop the entry), or the\n"
                + "controller method was removed. Stale entries:\n  - "
                + String.join("\n  - ", staleLegacyEntries))
        .isEmpty();
  }

  /** Helper for the stale-legacy-drift detector — re-runs the scan ignoring all allowlists. */
  private Set<String> scanCurrentViolations() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.navneet.executespec");
    Set<String> v = new TreeSet<>();
    for (var cls : classes) {
      boolean isController =
          cls.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")
              || cls.isAnnotatedWith("org.springframework.stereotype.Controller");
      if (!isController) continue;
      String classBasePath = extractClassBasePath(cls);
      for (JavaMethod m : cls.getMethods()) {
        if (!isHandlerMethod(m)) continue;
        String fullPath = classBasePath + extractMethodPath(m);
        if (!touchesProtectedSegment(fullPath)) continue;
        boolean callsGuard = callsTenantGuard(m);
        if (!callsGuard) v.add(cls.getName() + "#" + m.getName());
      }
    }
    return v;
  }

  /**
   * A handler "calls a tenant guard" iff it invokes a method ON one of {@link #GUARD_FQNS} (the
   * canonical {@code RunAccessGuard} / {@code ProjectAccessGuard} — covering {@code assertCanRead}
   * / {@code assertCanWrite} / {@code requireRunAccess}), OR it invokes a name-pattern guard that
   * is NOT a controller-local non-tenant helper ({@link #EXCLUDED_LOCAL_HELPERS}).
   *
   * <p>The exclusion is the bite of security-review-2026-05-25: {@code requireAuth} / {@code
   * ensureLinked} only prove "authenticated" / "repo linked", never "caller's org owns this
   * project", so they must NOT satisfy the requirement — otherwise a cross-org IDOR passes the
   * build.
   */
  private static boolean callsTenantGuard(JavaMethod m) {
    return m.getMethodCallsFromSelf().stream()
        .anyMatch(
            call -> {
              String calledName = call.getTarget().getName();
              if (GUARD_FQNS.contains(call.getTargetOwner().getName())) {
                return true;
              }
              return !EXCLUDED_LOCAL_HELPERS.contains(calledName)
                  && GUARD_METHOD_NAME_PATTERN.matcher(calledName).matches();
            });
  }

  private static boolean isHandlerMethod(JavaMethod m) {
    return m.isAnnotatedWith(GetMapping.class)
        || m.isAnnotatedWith(PostMapping.class)
        || m.isAnnotatedWith(PutMapping.class)
        || m.isAnnotatedWith(DeleteMapping.class)
        || m.isAnnotatedWith(PatchMapping.class)
        || m.isAnnotatedWith(RequestMapping.class);
  }

  /** A mutating HTTP verb (POST/PUT/DELETE/PATCH) — not a pure read GET. */
  private static boolean isWriteHandler(JavaMethod m) {
    return m.isAnnotatedWith(PostMapping.class)
        || m.isAnnotatedWith(PutMapping.class)
        || m.isAnnotatedWith(DeleteMapping.class)
        || m.isAnnotatedWith(PatchMapping.class);
  }

  /**
   * True iff the handler calls a read guard ({@code ProjectAccessGuard.assertCanRead} or {@code
   * RunAccessGuard.requireRunAccess}) and never calls the matching write guard ({@code
   * ProjectAccessGuard.assertCanWrite}, {@code RunAccessGuard.requireRunWriteAccess}, or {@code
   * RunAccessGuard.assertWriteAccess}). Read guards admit users who can view the resource but must
   * not mutate lifecycle, workspace, SCM, gate, test, preview, or share state.
   */
  private static boolean gatesWriteBehindReadGuard(JavaMethod m) {
    boolean callsProjectRead = false;
    boolean callsProjectWrite = false;
    boolean callsRunRead = false;
    boolean callsRunWrite = false;
    for (var call : m.getMethodCallsFromSelf()) {
      String owner = call.getTargetOwner().getName();
      String n = call.getTarget().getName();
      if ("com.navneet.executespec.project.security.ProjectAccessGuard".equals(owner)) {
        if ("assertCanRead".equals(n)) callsProjectRead = true;
        if ("assertCanWrite".equals(n)) callsProjectWrite = true;
      }
      if ("com.navneet.executespec.auth.security.RunAccessGuard".equals(owner)) {
        if ("requireRunAccess".equals(n)) callsRunRead = true;
        if ("requireRunWriteAccess".equals(n) || "assertWriteAccess".equals(n)) {
          callsRunWrite = true;
        }
      }
    }
    return (callsProjectRead && !callsProjectWrite) || (callsRunRead && !callsRunWrite);
  }

  private static String extractClassBasePath(com.tngtech.archunit.core.domain.JavaClass cls) {
    var rm = cls.tryGetAnnotationOfType(RequestMapping.class);
    if (rm.isEmpty()) return "";
    String[] paths = rm.get().value();
    return paths.length > 0 ? paths[0] : "";
  }

  private static String extractMethodPath(JavaMethod m) {
    return tryGet(m, GetMapping.class)
        .or(() -> tryGet(m, PostMapping.class))
        .or(() -> tryGet(m, PutMapping.class))
        .or(() -> tryGet(m, DeleteMapping.class))
        .or(() -> tryGet(m, PatchMapping.class))
        .or(() -> tryGet(m, RequestMapping.class))
        .orElse("");
  }

  private static java.util.Optional<String> tryGet(
      JavaMethod m, Class<? extends java.lang.annotation.Annotation> ann) {
    if (!m.isAnnotatedWith(ann)) return java.util.Optional.empty();
    String[] paths = invokeValue(m.getAnnotationOfType(ann));
    return paths.length > 0 ? java.util.Optional.of(paths[0]) : java.util.Optional.of("");
  }

  /**
   * Reflectively read the {@code value()} array on any of the @*Mapping annotation types — they all
   * expose a {@code String[] value()} but don't share a common base interface.
   */
  private static String[] invokeValue(Object annotation) {
    try {
      java.lang.reflect.Method valueMethod = annotation.getClass().getDeclaredMethod("value");
      Object result = valueMethod.invoke(annotation);
      return result instanceof String[] arr ? arr : new String[0];
    } catch (Exception e) {
      return new String[0];
    }
  }

  private static boolean touchesProtectedSegment(String path) {
    if (path == null) return false;
    return PROTECTED_SEGMENTS.stream().anyMatch(path::contains);
  }

  /** Diagnostic — print the controller surface so a human can audit the bucket assignment. */
  @SuppressWarnings("unused")
  private static String dumpControllerSurface(JavaClasses classes) {
    return classes.stream()
        .filter(
            c ->
                c.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    || c.isAnnotatedWith("org.springframework.stereotype.Controller"))
        .map(c -> c.getSimpleName())
        .collect(Collectors.joining("\n"));
  }
}
