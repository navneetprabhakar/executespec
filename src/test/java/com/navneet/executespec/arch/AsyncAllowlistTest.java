package com.navneet.executespec.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

/**
 * Regression guard for the 3a17e9aa stuck-EXECUTING bug: an {@code @Async} method accessed a
 * Hibernate lazy collection and threw {@code LazyInitializationException}, which killed the
 * terminal status update and left runs stuck in EXECUTING forever.
 *
 * <p>A proper rule — <em>{@code @Async} methods must not dereference LAZY JPA collections</em> —
 * requires deep bytecode analysis the codebase can't afford in a unit test. Instead, this test
 * maintains a <em>curated allowlist</em> of every {@code @Async} method in the codebase. Adding a
 * new one fails the test until the developer either:
 *
 * <ol>
 *   <li>adds it to the list below, AND
 *   <li>has consciously verified the method is either (a) not traversing a lazy JPA collection, (b)
 *       has an open session (e.g. {@code @Transactional} or {@code open-in-view=true} in effect),
 *       or (c) uses an eager-load repository query (e.g. {@code findWithRepoMappingsBy...}).
 * </ol>
 *
 * <p>When this test fails, do not "just add the method to the list" — actually verify the method is
 * safe first. The bug this guards against was a PARTIAL run that looked COMPLETE to the user for 30
 * minutes.
 */
class AsyncAllowlistTest {

  /**
   * Current set of {@code @Async} methods as of v2.5.12. Each entry: {@code <simple class
   * name>#<method name>}.
   *
   * <p>When you add or remove an {@code @Async} method, update this set and note why the addition
   * is safe in the PR description.
   */
  private static final Set<String> APPROVED =
      new TreeSet<>(
          Set.of(
              // auth/service/MailService — takes primitive args, no JPA access
              "MailService#sendVerificationEmail",
              "MailService#sendPasswordResetEmail",
              "MailService#sendTemporaryPasswordEmail",
              "MailService#sendOrgInviteEmail",
              // V175 — external project-collaboration invite. Takes primitive args
              // (toEmail, projectName, inviterEmail, acceptUrl, expiresInDays);
              // Thymeleaf render + SMTP send only; no JPA navigation. Same safety
              // profile as every other MailService#send* entry in this list.
              "MailService#sendProjectInviteEmail",
              // R-162 (Wave 2) — referral magic-link email. Takes primitive String
              // args (toEmail, referrerName, token); Thymeleaf render + SMTP send only;
              // no JPA nav. Same safety profile as the other 18 MailService#send*
              // entries. The allowlist drift was missed in the Wave 2 closeout — this
              // entry catches it up.
              "MailService#sendV3ReferralInvite",
              "MailService#sendEscalationToAdmin",
              "MailService#sendInviteRejectedEmail",
              "MailService#sendNewUserAlertToAdmin",
              "MailService#sendWeeklyAdminSummary", // v2.6.0 — Thymeleaf render + SMTP send, no JPA
              // access
              "MailService#sendAdminReminderEmail", // v2.6.0 — Thymeleaf render + SMTP send, no JPA
              // access
              "MailService#sendContactFeedbackEmail",
              "MailService#sendWelcomeEmail",
              "MailService#sendTierUpgradeEmail",
              "MailService#sendSuspensionEmail",
              "MailService#sendBonusRunsEmail",
              "MailService#sendInvoiceEmail",
              "MailService#sendDunningReminder",
              "MailService#sendRenewalReminder",
              "MailService#sendOrgUsageReport",
              "MailService#sendUserUsageReport",

              // billing/service — all take primitive args, no JPA access
              "BillingNotificationService#sendSubscriptionActivated",
              "BillingNotificationService#sendPaymentFailed",
              "BillingNotificationService#sendDowngradedToFree",
              "BillingNotificationService#sendCancellationConfirmed",
              "BillingNotificationService#sendTopupCredited",
              "BillingNotificationService#sendUpgradeRefund",
              "BillingNotificationService#sendUsageWarning",
              "InvoiceEmailService#sendInvoice",
              "RunQuotaRefundListener#onRunFailed",

              // notification — reads simple scalar fields on PipelineRun, no lazy collections
              "RunCompletionNotifier#onRunCompleted",
              "RunCompletionNotifier#onRunFailed",
              "RunCompletionNotifier#onGateOpened",
              "RunCompletionNotifier#onQuotaWarning",

              // sse — pushes heartbeat/events only, no JPA access
              "RunDetailSseService#onRunEvent",

              // execution orchestration — originally triggered 3a17e9aa bug;
              // fixed via findWithRepoMappingsBy* eager-load queries + compensation logic
              "ExecutionOrchestrator#triggerAnalysis",
              "ExecutionOrchestrator#approvePlanAndExecute",
              "ExecutionOrchestrator#executeApprovedPlan",
              "ExecutionPlannerService#generatePlan",

              // project workspace — @Async + writeRawFile (file I/O, DB read only)
              "ProjectWorkspaceWarmer#warmDiskCache",
              "ProjectDocumentService#indexAsync",

              // deployment — reads the run then uploads to GCS; no lazy nav
              "WorkspaceGcsUploadListener#onRunCompleted",
              "WorkspaceGcsUploadListener#onRunFailed",

              // v2 post-merge orchestrator + recovery
              "PostMergeOrchestrator#onMergeCompleted",
              "StartupRunResumer#onApplicationReady",

              // programbench — async background that polls run row + tars submission.
              // Reads the PipelineRun via id (no lazy navigation), shells out to docker.
              "ProgramBenchTaskService#captureOnTerminalAsync",

              // Wave 2.1 — activation instrumentation. Tracks events to PostHog +
              // user_activation_event row. Takes primitive args (userId, enum, map);
              // no lazy JPA nav. Exceptions never propagate — analytics is a
              // side-effect, not a pre-condition. See
              // docs/plans/activation-instrumentation-plan.md.
              "EventTrackingService#track",

              // E1.9 — activity foundation. Takes primitive args (UUID, enum,
              // String, Map<String,Object>); no JPA nav at all (the entity is
              // built in-method from primitives and saved). Internal try/catch
              // swallows any persist failure per the plan §1.9 non-blocking
              // contract. See docs/plans/pending-enterprise-readiness-execution-eval.md
              // §8 (E1.8/9/10 bundle).
              "ActivityEventEmitter#emit",

              // E1.5 — bootstrap PR flow. Loads ProjectRepo by id (no eager
              // collection nav — it has no @OneToMany relations) and saves
              // back primitive field updates. Outer try/catch wraps the whole
              // method so async-thread crashes never go silent. See
              // docs/plans/pending-enterprise-readiness-execution-eval.md §8 (E1.5).
              "BootstrapService#bootstrapAsync",

              // 2026-05-04 P0 fix #3 — splits the post-merge cascade so the
              // per-project heavy work (staleness invalidate + arch regen)
              // runs off the request thread. Takes a primitive String
              // projectId; no JPA navigation in this method body.
              // ProjectArchitectureService.generate owns its own per-save txs
              // (project/CLAUDE.md v2.5.2 note: "no longer @Transactional —
              // long LLM calls inside a tx tripped Hikari's 60s leak
              // detector"). Internal try/catch on every step ensures
              // async-thread crashes never go silent.
              "PostMergeCascadeService#regenerateAfterMerge",

              // 2026-05-04 — boot-time crash recovery for ExecutionRuns left
              // in EXECUTING after a JVM restart. Reads ExecutionRun rows
              // (no eager collection nav — `findStaleNonTerminal` doesn't
              // join solutions/dependencies) and re-dispatches each via
              // executeApprovedPlan. Outer try/catch wraps the whole sweep
              // so an exception never propagates to startup. Pairs with
              // AdminReconciliationService#onApplicationReady which handles
              // the non-resumable upstream phases.
              "ExecutionOrchestrator#onApplicationReady",

              // 2026-05-04 — boot-time fail-clean for orphaned ExecutionRuns
              // in non-resumable phases (SETUP / ANALYSING / ARCH_REVIEW /
              // PLANNING / PAUSED). Coordinates with
              // ExecutionOrchestrator#onApplicationReady — that resumes
              // EXECUTING; this fails the rest with a tight 1-min cutoff.
              // Reads ExecutionRun rows + saves status; no lazy collection
              // navigation. Outer try/catch swallows recovery failures so
              // they never block startup.
              "AdminReconciliationService#onApplicationReady"));

  @Test
  void asyncMethodAllowlistInSync() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.navneet.executespec");

    Set<String> actual =
        classes.stream()
            .flatMap(c -> c.getMethods().stream())
            .filter((JavaMethod m) -> m.isAnnotatedWith(Async.class))
            .map(m -> simpleName(m.getOwner().getName()) + "#" + m.getName())
            .collect(Collectors.toCollection(TreeSet::new));

    // Fail loudly when the real set drifts from the allowlist in either
    // direction. The message surfaces added + removed methods side-by-side.
    Set<String> added = new TreeSet<>(actual);
    added.removeAll(APPROVED);
    Set<String> removed = new TreeSet<>(APPROVED);
    removed.removeAll(actual);

    String msg =
        "AsyncAllowlist drift detected. "
            + (added.isEmpty()
                ? ""
                : "\n"
                    + "  NEW @Async methods (review for lazy-collection safety, then add to"
                    + " APPROVED): "
                    + added)
            + (removed.isEmpty()
                ? ""
                : "\n  REMOVED @Async methods (drop from APPROVED): " + removed)
            + "\n\nContext: this allowlist guards against the 3a17e9aa"
            + " stuck-EXECUTING bug (LazyInitializationException in @Async).";

    assertEquals(APPROVED, actual, msg);
  }

  private static String simpleName(String fqcn) {
    int dot = fqcn.lastIndexOf('.');
    return dot < 0 ? fqcn : fqcn.substring(dot + 1);
  }
}
