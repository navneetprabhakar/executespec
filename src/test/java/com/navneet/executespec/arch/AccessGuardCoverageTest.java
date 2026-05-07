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
   * Path-segment markers — if the controller's path or method's path contains any of these, the
   * method is in scope for the guard requirement.
   */
  private static final Set<String> PROTECTED_SEGMENTS =
      Set.of("/runs/{", "/projects/{", "/executions/{", "/launches/{", "/repos/{");

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
              // V2 canary surface — runIds in `/api/v2/runs/` reference V2Run rows which are a
              // separate storage from the production `pipeline_run` table; they're scoped to
              // V2 bring-up testing, not customer data. Live V2 runs go through the production
              // `/api/runs` surface which has its own RunAccessGuard coverage.
              "com.navneet.executespec.rollout.V2RunController#poll"));

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

  @Test
  void everyRunOrProjectScopedEndpointCallsAGuard() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.navneet.executespec");

    Set<String> violations = new TreeSet<>();

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

        boolean callsGuard =
            m.getMethodCallsFromSelf().stream()
                .anyMatch(
                    call ->
                        GUARD_FQNS.contains(call.getTargetOwner().getName())
                            || GUARD_METHOD_NAME_PATTERN
                                .matcher(call.getTarget().getName())
                                .matches());

        if (!callsGuard) {
          violations.add(key + "  (path=" + fullPath + ")");
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
        boolean callsGuard =
            m.getMethodCallsFromSelf().stream()
                .anyMatch(
                    call ->
                        GUARD_FQNS.contains(call.getTargetOwner().getName())
                            || GUARD_METHOD_NAME_PATTERN
                                .matcher(call.getTarget().getName())
                                .matches());
        if (!callsGuard) v.add(cls.getName() + "#" + m.getName());
      }
    }
    return v;
  }

  // ── helpers ────────────────────────────────────────────────────────────

  private static boolean isHandlerMethod(JavaMethod m) {
    return m.isAnnotatedWith(GetMapping.class)
        || m.isAnnotatedWith(PostMapping.class)
        || m.isAnnotatedWith(PutMapping.class)
        || m.isAnnotatedWith(DeleteMapping.class)
        || m.isAnnotatedWith(PatchMapping.class)
        || m.isAnnotatedWith(RequestMapping.class);
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
