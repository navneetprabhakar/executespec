package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
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
 * / @DeleteMapping / @PatchMapping} whose containing class has a {@code @RequestMapping}
 * (or {@code @*Mapping} on the method) path containing one of the protected segments must EITHER:
 *
 * <ul>
 *   <li>call {@link com.navneet.executespec.auth.security.RunAccessGuard} or
 *       {@link com.navneet.executespec.project.security.ProjectAccessGuard} directly, OR
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
   *   <li>{@code assertXxx} (e.g. assertCanRead, assertAccess, assertCanCreateProject) — alternative
   *       verb for the same throws-on-miss contract.
   *   <li>{@code ensureXxx} (e.g. ensureMember, ensureCallerIsOrgAdmin) — third verb.
   *   <li>{@code loadForXxx} / {@code loadXxxForRead} / {@code loadXxxForWrite} — the pattern
   *       used by repository-style guards that load + check ownership in one call.
   *   <li>{@code checkAccess} / {@code checkPermission} — explicit permission-check verbs.
   *   <li>{@code authorize} (Spring Security pattern).
   * </ul>
   *
   * <p>Any of these called from inside the handler body counts. This is the pragmatic baseline:
   * the existence of an access-named method call IS the signal that the controller author thought
   * about tenant access at all. Consolidating onto the canonical {@link
   * com.navneet.executespec.auth.security.RunAccessGuard} / {@link
   * com.navneet.executespec.project.security.ProjectAccessGuard} is a separate cleanup, tracked
   * as P4.1-followup in the GA-readiness plan.
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
   *   <li>The endpoint is gated by a SUPER_ADMIN role check in {@code SecurityConfig} and the
   *       admin role is the trust boundary.
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
              "com.navneet.executespec.sharing.controller.ProjectSharePublicController#getArchitecture"));

  /**
   * <b>Known legacy drift</b> — controller methods that handle a run/project/execution id but
   * don't yet call any access guard. These are documented tech debt, scheduled for migration
   * under P4.1-followup of the GA-readiness plan.
   *
   * <p>Each entry below is one tenant-leak risk that the audit explicitly flagged as catastrophic
   * (Risk #5). Migrating each to a {@link com.navneet.executespec.auth.security.RunAccessGuard} /
   * {@link com.navneet.executespec.project.security.ProjectAccessGuard} call is straightforward
   * but touches 37 sites — too much to land in one batch without proper test coverage per
   * controller. Tracked as a separate workstream so this test starts catching NEW drift today.
   *
   * <p><b>Rule:</b> entries can ONLY be removed from this set, never added. A new entry means a
   * new tenant-leak risk shipped without a guard — the test fails to make the gap visible.
   *
   * <p>SUPER_ADMIN-gated endpoints (paths starting with {@code /api/admin/}) are admitted here
   * because the SUPER_ADMIN role itself is the trust boundary; we still want the migration path
   * to consolidate them on a single canonical guard call (e.g. {@code requireSuperAdmin}) for
   * future review-time clarity.
   */
  private static final Set<String> KNOWN_LEGACY_DRIFT =
      new LinkedHashSet<>(
          Set.of(
              // E1.8/9/10 — activity feed needs ProjectAccessGuard.assertCanRead
              "com.navneet.executespec.activity.ActivityFeedController#list",
              // E3.12 — ADR endpoints need ProjectAccessGuard.assertCanRead
              "com.navneet.executespec.adr.AdrController#get",
              "com.navneet.executespec.adr.AdrController#list",
              // E3.14 — invariant endpoints need ProjectAccessGuard.assertCanRead
              "com.navneet.executespec.arch.invariants.InvariantController#list",
              "com.navneet.executespec.arch.invariants.InvariantController#recentViolations",
              // ProjectController — needs ProjectAccessGuard.assertCanRead /
              // assertCanWrite (members + settings mutate access controls themselves so
              // need stricter guards)
              "com.navneet.executespec.auth.controller.ProjectController#addMember",
              "com.navneet.executespec.auth.controller.ProjectController#archiveProject",
              "com.navneet.executespec.auth.controller.ProjectController#getProject",
              "com.navneet.executespec.auth.controller.ProjectController#listMembers",
              "com.navneet.executespec.auth.controller.ProjectController#removeMember",
              "com.navneet.executespec.auth.controller.ProjectController#updateSettings",
              // SuperAdminController — SUPER_ADMIN role gates these in SecurityConfig but
              // they still bind run ids; consolidate on requireSuperAdmin for review-time
              "com.navneet.executespec.auth.controller.SuperAdminController#forceFailTask",
              "com.navneet.executespec.auth.controller.SuperAdminController#reReviewRun",
              // V172 — code-review needs ProjectAccessGuard.assertCanRead
              "com.navneet.executespec.codereview.CodeReviewController#latest",
              "com.navneet.executespec.codereview.CodeReviewController#listForProject",
              // E4.7 — export needs ProjectAccessGuard.assertCanRead (returns full project state)
              "com.navneet.executespec.export.ProjectExportController#export",
              // /api/admin/ — SUPER_ADMIN gated by SecurityConfig
              "com.navneet.executespec.folder.ExecutespecMigrationController#migrate",
              // ProjectRepoController — repo-scoped, needs assertCanRead/Write on the project
              // that owns the repo (or org-level for ORG-scoped repos)
              "com.navneet.executespec.project.controller.ProjectRepoController#deleteRepo",
              "com.navneet.executespec.project.controller.ProjectRepoController#installRepoWebhook",
              "com.navneet.executespec.project.controller.ProjectRepoController#linkToProject",
              "com.navneet.executespec.project.controller.ProjectRepoController#listProjectRepos",
              "com.navneet.executespec.project.controller.ProjectRepoController#syncRepo",
              "com.navneet.executespec.project.controller.ProjectRepoController#unlinkFromProject",
              "com.navneet.executespec.project.controller.ProjectRepoController#updateRelationships",
              // PR endpoints — need RunAccessGuard.requireRunAccess (run-scoped)
              "com.navneet.executespec.project.pr.PullRequestController#listPrsForRun",
              "com.navneet.executespec.project.pr.PullRequestController#refreshProjectPrs",
              // RAG documents — need ProjectAccessGuard.assertCanRead/Write
              "com.navneet.executespec.project.rag.ProjectDocumentController#delete",
              "com.navneet.executespec.project.rag.ProjectDocumentController#list",
              "com.navneet.executespec.project.rag.ProjectDocumentController#upload",
              // Spec templates — need ProjectAccessGuard.assertCanRead/Write
              "com.navneet.executespec.project.template.SpecTemplateController#createForProject",
              "com.navneet.executespec.project.template.SpecTemplateController#delete",
              "com.navneet.executespec.project.template.SpecTemplateController#listForProject",
              // V172 — PR review project rollup needs ProjectAccessGuard.assertCanRead
              "com.navneet.executespec.prreview.PrReviewController#listForProject",
              // E3 — standards compliance report needs ProjectAccessGuard.assertCanRead
              "com.navneet.executespec.standards.report.StandardsComplianceController#report",
              // Test endpoints — need RunAccessGuard.requireRunAccess
              "com.navneet.executespec.testgen.TestResultController#applyTestFixes",
              "com.navneet.executespec.testgen.TestResultController#runTests",
              // V2 polling — needs RunAccessGuard.requireRunAccess
              "com.navneet.executespec.v2.rollout.V2RunController#poll"));

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
            + "     (only valid reasons: public-by-design, SUPER_ADMIN-gated, or no-tenant-bind).\n\n"
            + "Do NOT extend KNOWN_LEGACY_DRIFT — that set is closed; new entries indicate a\n"
            + "regression. Migrating the legacy set is tracked under P4.1-followup of the\n"
            + "GA-readiness plan.\n\n"
            + "Violations:\n  - "
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

  private static java.util.Optional<String> tryGet(JavaMethod m, Class<? extends java.lang.annotation.Annotation> ann) {
    if (!m.isAnnotatedWith(ann)) return java.util.Optional.empty();
    String[] paths = invokeValue(m.getAnnotationOfType(ann));
    return paths.length > 0 ? java.util.Optional.of(paths[0]) : java.util.Optional.of("");
  }

  /**
   * Reflectively read the {@code value()} array on any of the @*Mapping annotation types — they
   * all expose a {@code String[] value()} but don't share a common base interface.
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
