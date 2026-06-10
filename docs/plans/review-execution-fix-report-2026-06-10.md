# Review Execution Fix Report - 2026-06-10

## Scope

This report covers the fix batch executed from the `review/` documents on 2026-06-10. The source of truth for prioritization was the review folder, especially:

- `review/checkpoints/be-orchestration.md`
- `review/checkpoints/be-state-exec.md`
- `review/checkpoints/be-tools-reviewer.md`
- `review/checkpoints/be-bench-eval.md`
- `review/checkpoints/infra-build.md`
- `review/checkpoints/tb-sweep-plan.md`

Model matrix, Fireworks, OpenRouter, and broader provider routing work was intentionally left for the end, per instruction. The current working tree already had separate model/provider changes before this batch; those files were not treated as part of this fix report unless explicitly named below.

## What Changed

### 1. Terminal status preservation across late pipeline writes

Problem:

The orchestration review identified a correctness hole where a late pipeline write could overwrite an externally set terminal or pause state. The dangerous cases are cancellation, pause, resumed completion, retry completion, and patch-mode finalization. The practical failure mode is not cosmetic: a run can be cancelled or paused, but a late worker/reviewer path can still write a new terminal state and allow follow-up actions to treat the run as successfully complete.

Fix:

- `RunPersistenceWriter.updateStatus(...)` now checks the current persisted run before saving.
- If the current status is already terminal, the writer skips the stale write.
- If the current status is `PAUSED`, the writer also skips the stale write.
- Active, non-terminal runs still persist and broadcast normally.

Changed file:

- `src/main/java/com/navneet/executespec/integration/RunPersistenceWriter.java`

Verification:

- `RunPersistenceWriterTest.updateStatusDoesNotClobberCancelledRun`
- `RunPersistenceWriterTest.updateStatusDoesNotClobberPausedRun`
- `RunPersistenceWriterTest.updateStatusPersistsAndBroadcastsActiveRun`

Working status:

- Unit-proven for direct stale writer protection.
- Compile-proven for callers.
- Not yet live-proven with a real concurrent cancellation while a worker thread is still unwinding.

### 2. Centralized terminate path for patch-mode outcomes

Problem:

Patch-mode had success and failure paths that manually updated status, cost, and events rather than using the central `terminate(...)` path. That created drift from BUILD-mode behavior and made cancellation/pause preservation harder to reason about.

Fix:

- Patch-mode success now calls `terminate(...)`.
- Patch-mode error now calls `terminate(...)`.
- Patch-mode finding auto-push now checks the persisted final status after termination instead of trusting the intended in-memory status.

Changed file:

- `src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`

Verification:

- Backend compile passed.
- The terminal writer tests cover the underlying stale-status guard that patch mode now depends on.

Working status:

- Code-path wired and compile-proven.
- Not yet live-proven with an authenticated patch-mode finding run, because that would require a real backend run and likely LLM/provider spend.

### 3. `statusReason` preserved in lifecycle finalization

Problem:

The review called out status/reason drift risk. Some paths had a computed final status reason but called a terminate API that only accepted status and note, so the reason could be lost or replaced by default behavior.

Fix:

- `RunControlPort` now exposes a 5-argument terminate overload:
  - `runId`
  - `finalStatus`
  - `statusReason`
  - `note`
  - `startedAt`
- The old 4-argument overload remains as a default method for compatibility.
- `RunLifecycleService.finalizeRunAfterResume(...)` passes the computed `statusReason`.
- `RunLifecycleService.retryTask(...)` passes the retry terminal `statusReason`.

Changed files:

- `src/main/java/com/navneet/executespec/integration/RunControlPort.java`
- `src/main/java/com/navneet/executespec/integration/RunLifecycleService.java`
- `src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`

Verification:

- Backend compile passed.
- Existing call sites compile through the compatibility overload.

Working status:

- Compile-proven.
- Runtime behavior is not separately integration-proven because no real paused/resumed or retry run was executed.

### 4. Termination now acts on actual persisted status

Problem:

Even if `terminate(...)` is called with `COMPLETE`, the persisted run may already be `CANCELLED` or `PAUSED`. Follow-up effects must use the actual persisted status, not the attempted status. Otherwise a cancelled run can still emit completion side effects or trigger push logic.

Fix:

- `RunOrchestrator.terminate(...)` rereads the run before writing.
- It preserves existing `CANCELLED`.
- It preserves existing `PAUSED`.
- It calculates the effective terminal status from the saved run after the write.
- Completion events, metrics, referral activation, and SSE close are driven by the effective persisted status.
- `PAUSED` does not close the broadcaster stream.
- `startedAt == null` no longer risks `Duration.between(null, now)`; it falls back to persisted `startedAt`, then `Instant.now()`.

Changed file:

- `src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`

Verification:

- Backend compile passed.
- Writer-level terminal preservation is unit-proven.

Working status:

- Compile-proven and unit-supported.
- Full external side-effect validation still needs a live cancel/pause run with event stream observation.

### 5. Shell sandbox newline bypass closed

Problem:

The shell policy split command chains on shell operators such as `&&`, `||`, `;`, and `|`, but not raw newlines or carriage returns. A command such as:

```bash
cat package.json
bash -c '...'
```

could avoid segment-level analysis that would have caught the second command if it were separated by `;`.

Fix:

- `ShellSandboxPolicy.splitSegments(...)` now splits on raw newline and carriage return outside quotes.
- Quoted newlines remain part of the quoted argument and do not create false command segments.

Changed file:

- `src/main/java/com/navneet/executespec/tool/ShellSandboxPolicy.java`

Verification:

- `ShellSandboxPolicyTest.newlineSeparatedCommandsCannotBypassBlockedSecondCommand`
- `ShellSandboxPolicyTest.quotedNewlinesDoNotSplitCommandSegments`

Working status:

- Unit-proven for the bypass pattern and for the important quoted-newline non-regression.

### 6. V2 write/edit paths now scan generated content

Problem:

The review called out an important asymmetry: request input could be scanned, but generated files written by the worker through `writeFile` and `editFile` were not scanned at the final content boundary. That leaves the main code-writing path able to persist reverse shells, metadata exfiltration helpers, or other blocked code patterns.

Fix:

- `WriteFileTool` now scans the content before creating or writing the target file.
- `EditFileTool` now constructs the full post-edit content, scans it, and only writes if it passes.
- If scanning fails:
  - the tool returns `ToolResult.Err(FORBIDDEN, ...)`
  - `writeFile` does not create the file
  - `editFile` leaves the original file unchanged

Changed files:

- `src/main/java/com/navneet/executespec/tool/WriteFileTool.java`
- `src/main/java/com/navneet/executespec/tool/EditFileTool.java`

Verification:

- `WriteFileToolTest.cleanContentIsWritten`
- `WriteFileToolTest.reverseShellContentIsBlockedBeforeWrite`
- `EditFileToolTest.reverseShellContentIsBlockedAndOriginalFileIsPreserved`
- Existing `EditFileToolTest` cases still pass after constructor injection change.

Working status:

- Unit-proven for clean write, blocked write, blocked edit, and file-preservation behavior.
- Not yet proven through a full WorkerAgent run, but the changed boundary is the tool dispatcher boundary the worker uses.

### 7. `RunTestsTool` host/container pytest command no longer interpolates test IDs into a shell string

Problem:

`RunTestsTool` already rejected metacharacters, but it still built a command string and sent it through `/bin/sh -c` for host execution and non-login container execution. That keeps shell parsing in the path and makes the safety model dependent on rejection completeness.

Fix:

- Host execution now uses ProcessBuilder args:
  - `python -m pytest <testIds...> --tb=short --no-header -q`
- Non-login container execution now uses direct docker exec args:
  - `docker exec -w <workdir> <containerId> python -m pytest <testIds...> --tb=short --no-header -q`
- SWE-bench login-shell container execution still uses bash for login environment, but test IDs are passed as positional args after `_` and consumed via `"$@"`, not interpolated into the script body.

Changed file:

- `src/main/java/com/navneet/executespec/tool/RunTestsTool.java`

Verification:

- `RunTestsToolTest.hostCommandUsesProcessArgsWithoutShell`
- `RunTestsToolTest.nonLoginContainerCommandUsesDockerExecArgsWithoutShell`
- `RunTestsToolTest.loginShellContainerPassesTestIdsAsPositionalArgs`
- `RunTestsToolTest.rejectsMetacharactersInTestIds`

Working status:

- Unit-proven for command shape and metacharacter rejection.
- Not live-proven against an actual SWE-bench cleanroom container in this turn.

### 8. TEAM tier no longer falls through to FREE rate limits

Problem:

The API-platform review found that the live paid TEAM tier was absent from every `RateLimitPolicy` limit map. A TEAM user therefore fell back to `DEFAULT`, which matched FREE limits for RUNS, READ, WRITE, PREVIEW, and DEPLOY.

Fix:

- Added explicit TEAM rows to every tiered rate-limit group.
- TEAM now shares the higher paid capacity used by ENTERPRISE for these endpoint groups.

Changed file:

- `src/main/java/com/navneet/executespec/ratelimit/RateLimitPolicy.java`

Verification:

- `RateLimitPolicyLevelTest.teamTierDoesNotFallBackToFreeLimits`

Working status:

- Unit-proven.

### 9. Payment history is scoped to the caller's org

Problem:

The billing review found that `GET /api/billing/payment-history?orgId=...` accepted caller-supplied `orgId` and required only an org/billing admin role. Since every solo user is org admin of a personal org, this allowed cross-org payment audit reads.

Fix:

- `PaymentAuditController.paymentHistory(...)` now receives the authenticated principal.
- SUPER_ADMIN can still read any org.
- Non-super callers can only read the org equal to `principal.user.orgId`.
- Cross-org access fails before calling `PaymentAuditService`.

Changed file:

- `src/main/java/com/navneet/executespec/billing/controller/PaymentAuditController.java`

Verification:

- `PaymentAuditControllerTest.paymentHistoryRejectsDifferentOrgForOrgAdmin`
- `PaymentAuditControllerTest.paymentHistoryAllowsOwnOrg`
- `PaymentAuditControllerTest.paymentHistoryAllowsSuperAdminForAnyOrg`

Working status:

- Unit-proven at controller boundary.

### 10. Deployment list/live endpoints are org-scoped for non-super users

Problem:

The API-platform review found that `GET /api/deployments` without `runId` and `GET /api/deployments/live` used global repository reads after only `requireOrgAdmin`. Because every normal user has personal-org admin status, this exposed deployment metadata across tenants.

Fix:

- Added org-scoped deployment repository queries joined through `PipelineRun.orgId`.
- Non-super callers now list only deployments whose run belongs to their org.
- SUPER_ADMIN retains the existing global view.

Changed files:

- `src/main/java/com/navneet/executespec/api/controller/DeploymentController.java`
- `src/main/java/com/navneet/executespec/state/repository/DeploymentRepository.java`

Verification:

- `DeploymentControllerTest.listWithoutRunIdUsesCallerOrgForNonSuperAdmin`
- `DeploymentControllerTest.liveUsesCallerOrgForNonSuperAdmin`
- `DeploymentControllerTest.superAdminListWithoutRunIdKeepsGlobalView`
- Backend compile passed, covering the JPQL repository signatures.

Working status:

- Unit-proven at controller/repository-call boundary.
- JPQL is compile-validated, not database-executed in this turn.

### 11. Standards report no longer accepts arbitrary org IDs

Problem:

The governance review found that `GET /api/projects/{projectId}/standards/report?orgId=...` guarded project read access but then used the caller-supplied `orgId` to resolve org standards. A user with access to one project could request another org's standards.

Fix:

- `StandardsComplianceController.report(...)` now uses the `Project` returned by `ProjectAccessGuard.assertCanRead`.
- If the request `orgId` does not match `project.orgId`, the controller returns 403.
- Effective standards resolution uses `project.orgId`, not caller-controlled input.

Changed file:

- `src/main/java/com/navneet/executespec/standards/report/StandardsComplianceController.java`

Verification:

- `StandardsComplianceControllerTest.reportRejectsOrgIdThatDoesNotOwnProject`
- `StandardsComplianceControllerTest.reportResolvesStandardsForProjectOrg`

Working status:

- Unit-proven at controller boundary.

### 12. Forked runs now consume the monthly run quota counter

Problem:

The API-platform review found that `POST /api/runs/{id}/fork` still called `assertCanSubmitRun`, the old non-incrementing quota probe. Normal submit had already moved to `assertAndIncrementRunCount`, but fork remained a dispatch path that did not consume the monthly quota counter.

Fix:

- `RunController.forkRun(...)` now calls `tierEnforcementService.assertAndIncrementRunCount(...)`, matching normal submit.

Changed file:

- `src/main/java/com/navneet/executespec/api/controller/RunController.java`

Verification:

- Backend compile passed.

Working status:

- Compile-proven.
- Not yet unit-proven through a dedicated fork controller test because `RunController` has a broad constructor and the full fork path dispatches a real run through the orchestrator seam.

### 13. Stripe checkout completion now bridges `cs_...` to `sub_...`

Problem:

The billing review found that Stripe subscribe persisted the checkout-session id (`cs_...`) in `billing_subscription.gateway_subscription_id`, while all Stripe webhook follow-ups looked up by Stripe subscription id (`sub_...`). Paid Stripe checkouts could therefore take money while the local subscription stayed `CREATED` and never activated.

Fix:

- `handleStripeCheckoutCompleted(...)` now reads the checkout session id from the payload.
- It first looks up the durable `sub_...` id.
- If absent, it falls back to the locally stored checkout-session id and rewrites `gatewaySubscriptionId` to the durable `sub_...` id.
- It then marks the local subscription `ACTIVE`, stores the Stripe customer id, and syncs the seat count as before.

Changed file:

- `src/main/java/com/navneet/executespec/billing/service/BillingService.java`

Verification:

- `BillingServiceStripeTest.stripeCheckoutCompletedRewritesCheckoutSessionIdToSubscriptionId`

Working status:

- Unit-proven through the public `processStripeWebhook(...)` seam.
- Not live-proven against a real Stripe webhook delivery.

### 14. Stripe cancellation now queues period-end cancellation

Problem:

The billing review found that `BillingService.cancel(...)` told the user and local row that cancellation was at period end, but called Stripe's immediate `cancelSubscription(...)` path. Stripe subscribers could lose the rest of a paid period.

Fix:

- Stripe user cancellation now calls `gatewayRouter.stripe().cancelAtPeriodEnd(...)`, matching Razorpay behavior and the local `cancelAtPeriodEnd=true` state.

Changed file:

- `src/main/java/com/navneet/executespec/billing/service/BillingService.java`

Verification:

- `BillingServiceStripeTest.stripeCancelUsesPeriodEndCancellation`

Working status:

- Unit-proven at gateway-call boundary.

### 15. Failed-run quota refund no longer over-refunds estimated credits

Problem:

The billing review found a charge/refund asymmetry: submit/fork increments the monthly run counter by one, but `RunQuotaRefundListener` refunded `creditsCharged` or `batchCredits`. A failed run estimated at five credits could therefore erase five monthly runs even though only one monthly run was charged.

Fix:

- `RunQuotaRefundListener` now refunds exactly one monthly run, matching the live dispatch-time quota charge.
- The comment now documents that `creditsCharged` is currently an estimated cost-credit field, not the monthly quota charge amount.

Changed file:

- `src/main/java/com/navneet/executespec/billing/service/RunQuotaRefundListener.java`

Verification:

- `RunQuotaRefundListenerTest.failedRunRefundsOneDispatchChargeNotEstimatedCreditsCharged`
- `RunQuotaRefundListenerTest.dirtyPartialDoesNotRefund`

Working status:

- Unit-proven.

### 16. Billing analytics endpoints are reachable to org admins and normalize entity type

Problem:

The billing review found the root cause of the Billing page showing `0 runs / $0`: the frontend called `/api/admin/analytics/...` with lowercase `entityType=org`, while `SecurityConfig` blocked `/api/admin/**` for every non-SUPER_ADMIN and the controller/service compared entity types case-sensitively.

Fix:

- `SecurityConfig` now permits authenticated access to `/api/admin/analytics/**` before the blanket `/api/admin/**` SUPER_ADMIN rule.
- `AdminAnalyticsController` normalizes `entityType` to uppercase before access checks and service calls.
- Invalid entity types now fail before reaching the service.
- The stale USER-scope ORG_ADMIN shortcut was removed; non-super users can read only their own USER analytics.
- `AnalyticsService` also normalizes entity type defensively.
- USER-scope SQL no longer filters `org_id IS NULL`, which made user analytics dead for personal-org users.

Changed files:

- `src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java`
- `src/main/java/com/navneet/executespec/api/controller/AdminAnalyticsController.java`
- `src/main/java/com/navneet/executespec/billing/service/AnalyticsService.java`

Verification:

- `AdminAnalyticsControllerTest.usageNormalizesLowercaseOrgEntityType`
- `AdminAnalyticsControllerTest.orgAdminCannotReadArbitraryUserAnalytics`
- `AdminAnalyticsControllerTest.invalidEntityTypeFailsBeforeServiceCall`
- Backend compile passed.

Working status:

- Controller behavior is unit-proven.
- Security matcher and SQL changes are compile-proven.
- Not yet browser-proven against a logged-in non-super billing page in this turn.

### 17. Invite/share-link revoke no longer acts on unscoped UUIDs

Problem:

The governance review found a path-authorized/action-unscoped IDOR shape: controllers authorized writes on `{projectId}`, but services loaded an invite/share-link by UUID and revoked it without checking that the loaded row belonged to the authorized project.

Fix:

- `ProjectInviteService.revoke(...)` now accepts the authorized `projectId` and compares it to the loaded invite's `projectId`.
- `ProjectShareLinkService.revoke(...)` now accepts the authorized `projectId` and compares it to the loaded link's `projectId`.
- Mismatches return 404 so callers cannot distinguish another project's valid UUID from a missing row.
- Controllers pass the path `projectId` through to the service.

Changed files:

- `src/main/java/com/navneet/executespec/sharing/service/ProjectInviteService.java`
- `src/main/java/com/navneet/executespec/sharing/controller/ProjectInviteController.java`
- `src/main/java/com/navneet/executespec/sharing/service/ProjectShareLinkService.java`
- `src/main/java/com/navneet/executespec/sharing/controller/ProjectShareLinkController.java`

Verification:

- `ProjectInviteServiceTest.revokeRejectsInviteFromAnotherProject`
- `ProjectInviteServiceTest.revokeMarksSameProjectInviteRevoked`
- `ProjectShareLinkServiceTest.revoke_rejects_link_from_another_project`
- Existing `ProjectShareLinkServiceTest.revoke_is_idempotent`
- Backend compile passed.

Working status:

- Unit-proven at service boundary.

### 18. Contract detection stores raw inline contract content

Problem:

The governance review found that `ContractDetectionService` manually JSON-escaped contract content before writing `contract_version.inline_content`. This breaks multiline contracts as JSONB and also makes later raw disk content comparisons differ from stored content even when the contract is unchanged.

Fix:

- Removed the hand-rolled JSON string writer.
- `ContractVersion.inlineContent` now receives the raw contract text; Hibernate/JDBC JSON mapping is responsible for persistence escaping.

Changed file:

- `src/main/java/com/navneet/executespec/contracts/detection/ContractDetectionService.java`

Verification:

- `ContractDetectionServiceTest.firstDetection_insertsRegistryAndVersionAndEmitsDetectedEvent` now captures the saved `ContractVersion` and asserts multiline raw content is preserved.

Working status:

- Unit-proven at service boundary.
- Not yet database-roundtrip-proven against PostgreSQL JSONB.

### 19. Feature flag reload no longer clears the live cache before repopulating

Problem:

The API-platform review found that scheduled feature-flag reload used `cache.clear()` followed by per-flag `put(...)`. Any concurrent flag read in that window could see a missing value and treat the flag as false/default.

Fix:

- Replaced the mutable `ConcurrentHashMap` cache with an `AtomicReference<Map<String,String>>`.
- Full reload builds a complete immutable snapshot and swaps it in one operation.
- Single-key updates and Redis pub/sub invalidations copy the current snapshot, update one key, and atomically publish the new snapshot.

Changed file:

- `src/main/java/com/navneet/executespec/config/featureflag/FeatureFlagService.java`

Verification:

- `FeatureFlagServiceTest.scheduledReloadSwapsToACompleteSnapshot`
- `FeatureFlagServiceTest.redisSingleKeyUpdatePublishesIntoSnapshot`
- `FeatureFlagServiceTest.updateWritesThroughLocalSnapshotAndPublishes`
- Backend compile passed.

Working status:

- Unit-proven for reload replacement and update paths.

### 20. Rate limit IP extraction now respects a trusted proxy chain

Problem:

The API-platform review found that rate limiting trusted the first `X-Forwarded-For` entry. If an edge or proxy forwards arbitrary user-supplied XFF without normalizing it, a client can rotate the first entry and bypass IP-scoped limits.

Fix:

- Added `rate-limiting.trusted-proxy-count` with default `1`.
- `RateLimitFilter` now derives the client IP from the entry immediately before the trusted proxy chain instead of blindly using the first XFF entry.
- Missing, blank, or malformed candidates fall back to `request.getRemoteAddr()`.
- The extraction helper is package-visible and unit-pinned so future proxy-topology changes are explicit.

Changed files:

- `src/main/java/com/navneet/executespec/ratelimit/RateLimitProperties.java`
- `src/main/java/com/navneet/executespec/ratelimit/RateLimitFilter.java`

Verification:

- `RateLimitFilterTest.extractClientIpUsesEntryBeforeOneTrustedProxyByDefault`
- `RateLimitFilterTest.extractClientIpSupportsTwoTrustedProxyHops`
- `RateLimitFilterTest.extractClientIpFallsBackToRemoteAddressWhenXffMissing`
- `RateLimitPolicyLevelTest.teamTierDoesNotFallBackToFreeLimits`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven for one-hop and two-hop trusted proxy extraction.
- Not yet proven against the actual Cloudflare/GCP header chain in dev/prod traffic.

### 21. Subscription upgrades no longer cancel the old paid subscription before checkout is paid

Problem:

The billing review found that selecting a higher tier immediately cancelled the existing gateway subscription, attempted a prorated refund, and marked the old local row `UPGRADED` before the replacement checkout completed. If the buyer abandoned checkout, the old paid subscription was gone while user/org tier fields could remain paid.

Fix:

- `subscribe(...)` now treats an upgrade as checkout initiation only. It validates that the target tier is higher but does not cancel, refund, or mutate the existing paid subscription.
- Successful activation now retires the old subscription immediately before the replacement row becomes `ACTIVE`.
- The activation hook handles both paid-old-subscription retirement and lingering FREE replacement, preserving the DB partial unique-index requirement around active subscriptions.
- Stripe activation paths call the same hook from `checkout.session.completed` and `invoice.paid`; Razorpay activation calls it from `verifyAndActivate`.

Changed files:

- `src/main/java/com/navneet/executespec/billing/service/BillingService.java`
- `src/test/java/com/navneet/executespec/billing/service/BillingServiceStripeTest.java`

Verification:

- `BillingServiceStripeTest.subscribeUpgradeDoesNotCancelExistingSubscriptionBeforeCheckoutIsPaid`
- `BillingServiceStripeTest.verifyAndActivateUpgradeRetiresExistingSubscriptionAfterPaymentSucceeds`
- Existing Stripe checkout id rewrite and period-end cancellation tests still pass.
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven for checkout-abandonment safety and activation-time old-subscription retirement.
- Not yet proven against real Stripe/Razorpay webhook ordering in a live payment sandbox.

### 22. Legacy billing seat endpoint now fails closed

Problem:

The billing review found that `POST /api/billing/seats` updated `BillingSubscription.totalSeats` and `extraSeats` locally without updating the payment gateway. That created a dark API path where billed quantity and granted seats could diverge.

Fix:

- `BillingService.updateSeats(...)` now fails closed with a `BillingException`.
- The supported seat-change path remains the org workflow (`/api/org/me/seats/adjust`), which pushes quantity to the gateway and then syncs local state.

Changed files:

- `src/main/java/com/navneet/executespec/billing/service/BillingService.java`
- `src/test/java/com/navneet/executespec/billing/service/BillingServiceStripeTest.java`

Verification:

- `BillingServiceStripeTest.updateSeatsFailsClosedInsteadOfMutatingLocalSubscriptionOnly`
- Full `BillingServiceStripeTest` still passes.
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven that the legacy service path no longer reads or writes subscription rows.
- Not yet proven with a live HTTP request to `/api/billing/seats`; expected behavior is a billing error response rather than silent local mutation.

### 23. FREE users can now spend valid top-up runs after exhausting monthly quota

Problem:

The billing review found that solo FREE users were rejected at the first quota gate as soon as `personalRunCount >= monthlyLimit`. The non-FREE and org branches checked top-up/bonus runs before rejecting, but the FREE branch threw `402` before consulting `RunTopupService`.

Fix:

- The FREE branch in `TierEnforcementService.assertUserQuota(...)` now checks `runTopupService.getAvailableBonus(userId)` before throwing the upgrade message.
- If bonus runs exist, the submit path is allowed and the existing post-increment path consumes one bonus run when the counter goes over the tier limit.

Changed files:

- `src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java`
- `src/test/java/com/navneet/executespec/auth/service/TierEnforcementServiceRunQuotaTest.java`

Verification:

- `TierEnforcementServiceRunQuotaTest.freeUserAtMonthlyLimitCanSpendAvailableTopupRun`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven for the solo FREE quota path.
- Not yet proven through a live top-up purchase plus run submission.

### 24. SUNSETTING subscriptions remain active until period end and are downgraded after expiry

Problem:

The billing review found that `TierLifecycleService` writes `CANCELLING_AT_PERIOD_END`, but the subscription repository did not treat that status as active and did not include it in the period-end downgrade scan. Subscribers queued by tier SUNSETTING could lose active-subscription resolution immediately and never be downgraded later.

Fix:

- `findActiveByUserId`, `findActiveByOrgId`, `findActiveByTier`, and `findAllActiveOrPastDue` now include `CANCELLING_AT_PERIOD_END`.
- `findCancelledPastPeriodEnd` now includes both `CANCELLED` and `CANCELLING_AT_PERIOD_END`.

Changed files:

- `src/main/java/com/navneet/executespec/billing/repository/BillingSubscriptionRepository.java`
- `src/test/java/com/navneet/executespec/billing/repository/BillingSubscriptionRepositoryQueryTest.java`

Verification:

- `BillingSubscriptionRepositoryQueryTest.activeSubscriptionQueriesIncludePeriodEndCancellationStatus`
- `BillingSubscriptionRepositoryQueryTest.periodEndDowngradeQueryIncludesSunsettingCancellationStatus`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven at repository query contract level.
- Not yet proven with a real database row plus scheduler execution.

### 25. Top-up verification now enforces order ownership

Problem:

The billing review found that `RunTopupService.verifyAndCredit(...)` accepted an authenticated `userId` but did not assert that the Razorpay order belonged to that user. A valid signed payment for one pending order could be credited through another caller path.

Fix:

- After signature validation and top-up lookup, `verifyAndCredit(...)` now compares `topup.userId` to the authenticated `userId`.
- Ownership mismatch throws before status, remaining credits, audit, notification, or invoice mutation.

Changed files:

- `src/main/java/com/navneet/executespec/billing/service/RunTopupService.java`
- `src/test/java/com/navneet/executespec/billing/service/RunTopupServiceTest.java`

Verification:

- `RunTopupServiceTest.verifyAndCreditRejectsOrderOwnedByAnotherUserBeforeMutation`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven for the cross-user ownership case.
- The separate duplicate-credit race between client verify and webhook processing is covered in section 26.

### 26. Top-up client verify and webhook processing now share atomic activation

Problem:

The billing review found that client-side top-up verification and the Razorpay `order.paid` / `payment.captured` webhook both read `status == PENDING` and then mutated the same row. Under concurrent delivery, both paths could generate invoice/accounting/notification side effects.

Fix:

- Added `RunTopupRepository.activatePendingPurchase(...)`, a conditional update from `PENDING` to `ACTIVE` keyed by `gatewayOrderId`.
- `RunTopupService.verifyAndCredit(...)` now uses the conditional update and skips side effects if another path already activated the order.
- `BillingService.handleOrderPaymentWebhook(...)` uses the same conditional update and skips invoice/email/notification side effects if it loses the activation race.

Changed files:

- `src/main/java/com/navneet/executespec/billing/repository/RunTopupRepository.java`
- `src/main/java/com/navneet/executespec/billing/service/RunTopupService.java`
- `src/main/java/com/navneet/executespec/billing/service/BillingService.java`
- `src/test/java/com/navneet/executespec/billing/service/RunTopupServiceTest.java`
- `src/test/java/com/navneet/executespec/billing/service/BillingServiceStripeTest.java`

Verification:

- `RunTopupServiceTest.verifyAndCreditUsesAtomicActivationBeforeSideEffects`
- `RunTopupServiceTest.verifyAndCreditSkipsSideEffectsWhenAtomicActivationLosesRace`
- `BillingServiceStripeTest.razorpayTopupWebhookSkipsSideEffectsWhenAtomicActivationLosesRace`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven for both client verify and webhook lost-race behavior.
- Not yet database-concurrency-proven with two real transactions racing on PostgreSQL.

### 27. TEAM seat adjustment now matches the one-seat product rule

Problem:

The billing review found that subscription checkout accepts TEAM with 1-20 seats, but `OrgService.adjustSeats(...)` still enforced a two-seat minimum and the org UI copy still said "min 2".

Fix:

- Changed `OrgService.TEAM_MIN_SEATS` from `2` to `1`.
- Updated IDE and Signal org seat-management copy to remove the stale "min 2" language.

Changed files:

- `src/main/java/com/navneet/executespec/auth/service/OrgService.java`
- `frontend/src/ide/IdeOrg.tsx`
- `frontend/src/signal/pages/LegalPages.tsx`
- `src/test/java/com/navneet/executespec/auth/service/OrgServiceSeatPolicyTest.java`

Verification:

- `OrgServiceSeatPolicyTest.teamSeatAdjustmentAllowsSingleSeatOrg`
- `npm --prefix frontend run build`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit/build-proven for the rule and frontend compilation.
- Not yet live-verified through an authenticated org seat-adjust HTTP call.

### 28. Invoice resend now emails the invoice owner

Problem:

The billing review found that `POST /api/admin/invoices/{id}/send` resent the invoice to the calling admin or finance user instead of the customer who owns the invoice.

Fix:

- `InvoiceController.resendInvoice(...)` now loads `invoice.userId` and sends to that user's email/name.
- Missing invoice owner now returns `404` instead of silently emailing the wrong person.

Changed files:

- `src/main/java/com/navneet/executespec/billing/controller/InvoiceController.java`
- `src/test/java/com/navneet/executespec/billing/controller/InvoiceControllerTest.java`

Verification:

- `InvoiceControllerTest.resendInvoiceSendsToInvoiceOwnerNotCallingAdmin`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven at controller boundary.
- Not yet live-verified through an authenticated admin HTTP request.

### 29. Admin credit-note API now rejects invalid amounts

Problem:

The billing review found that `InvoiceController.issueCreditNote(...)` accepted arbitrary `amountPaise`, including negative amounts and values greater than the original invoice total.

Fix:

- Rejects `amountPaise <= 0` before any invoice generation.
- Loads the original invoice and rejects credit-note amounts above `invoice.totalAmount`.

Changed files:

- `src/main/java/com/navneet/executespec/billing/controller/InvoiceController.java`
- `src/test/java/com/navneet/executespec/billing/controller/InvoiceControllerTest.java`

Verification:

- `InvoiceControllerTest.issueCreditNoteRejectsNegativeAmount`
- `InvoiceControllerTest.issueCreditNoteRejectsAmountGreaterThanInvoiceTotal`
- Existing invoice resend test still passes.
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven at controller boundary.
- Not yet live-verified through an authenticated finance/admin HTTP request.

### 30. GSTR-1 export no longer crashes on null GST rates

Problem:

The billing review found that `AccountingService.exportGstr1(...)` used integer `0` fallbacks for nullable `BigDecimal` GST rate fields while formatting with `%.2f`. Legacy rows with null rates could throw `IllegalFormatConversionException`.

Fix:

- Replaced integer fallbacks with `BigDecimal.ZERO` for CGST, SGST, and IGST rates.

Changed files:

- `src/main/java/com/navneet/executespec/billing/service/AccountingService.java`
- `src/test/java/com/navneet/executespec/billing/service/AccountingServiceTest.java`

Verification:

- `AccountingServiceTest.exportGstr1HandlesLegacyInvoicesWithNullGstRates`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven for null-rate export.
- Not yet verified against a real legacy invoice export file.

### 31. Purchased top-ups now retain their package id for invoice generation

Problem:

The billing review found that `RunTopupService.verifyAndCredit(...)` had code to resolve a top-up package from `grantReason="pkg:<id>"`, but `initiatePurchase(...)` never wrote that reference. Top-up invoices therefore always fell back to the generic package path.

Fix:

- `RunTopupService.initiatePurchase(...)` now stores `grantReason="pkg:<packageId>"` on the pending top-up row.

Changed files:

- `src/main/java/com/navneet/executespec/billing/service/RunTopupService.java`
- `src/test/java/com/navneet/executespec/billing/service/RunTopupServiceTest.java`

Verification:

- `RunTopupServiceTest.initiatePurchaseStoresPackageReferenceForInvoiceResolution`
- Existing top-up ownership and atomic activation tests still pass.
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven at purchase-initiation boundary.
- Not yet verified through a real purchase-to-invoice flow.

### 32. Reviewer grep verification now uses grep-compatible literal patterns

Problem:

The tools/reviewer review found that `AccuracyAssessmentService.verifyMissingByGrep(...)` passed `Pattern.quote(token)` into `FileSystemTool.searchFiles(...)`. `Pattern.quote(...)` emits Java regex markers like `\Qfoo\E`, but `searchFiles(...)` shells out to GNU `grep`, whose BRE engine does not understand those markers. The anti-hallucination demotion path could therefore miss real workspace evidence and leave requirements incorrectly marked missing.

Fix:

- Replaced Java-regex quoting with a grep-compatible literal pattern helper that escapes BRE metacharacters directly.
- Added a unit assertion that the helper does not emit `\Q...\E` and preserves literal matching for dots, brackets, dollars, stars, and backslashes.

Changed files:

- `src/main/java/com/navneet/executespec/reviewer/AccuracyAssessmentService.java`
- `src/test/java/com/navneet/executespec/reviewer/AccuracyAssessmentExtractKeywordsTest.java`

Verification:

- `AccuracyAssessmentExtractKeywordsTest.grep_literal_pattern_uses_grep_compatible_escaping`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven for pattern generation.
- Not yet verified with a real workspace-backed `FileSystemTool.searchFiles(...)` call and a live report demotion.

### 33. Background jobs are now cleaned at run end

Problem:

The tools/reviewer review found that `JobRegistry.cleanup(runId)` was implemented and unit-tested, but no run-end path called it. If `worker.background-jobs.enabled` were flipped on, forgotten background processes and temp logs could survive past the run and the in-memory job map could grow indefinitely.

Fix:

- Added `RunResourceCleaner`, a small best-effort cleanup seam for run-scoped resources.
- Wired `RunOrchestrator`'s guaranteed `finally` path to call `resourceCleaner.cleanupRun(runId)`.
- Kept failures non-fatal so cleanup cannot mask the original run terminal state.

Changed files:

- `src/main/java/com/navneet/executespec/integration/RunResourceCleaner.java`
- `src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`
- `src/test/java/com/navneet/executespec/integration/RunResourceCleanerTest.java`

Verification:

- `RunResourceCleanerTest`
- Existing `JobRegistryTest`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven that the run-end cleaner delegates to `JobRegistry.cleanup(...)` and swallows cleanup failures.
- `JobRegistryTest` already proves live jobs are killed and de-registered by `cleanup(runId)`.
- Not yet verified through a live WorkerAgent run that starts a `backgroundJob` and then completes/cancels.

### 34. WorkspaceContext propagation now carries the worktree override

Problem:

The tools/reviewer review found that `WorkspaceContextThreadLocalAccessor` documented propagation of `runId::worktreePath + taskId`, but the implementation captured only `taskId`. Across Micrometer context snapshots, child-thread file/git tools could therefore lose the task worktree override and fall back to the shared run workspace.

Fix:

- Added package-local `WorkspaceContext` snapshot/restore helpers for the raw worktree context plus task id.
- Updated `WorkspaceContextThreadLocalAccessor` to propagate the full snapshot instead of only the task id.
- Pinned clear semantics for both explicit null restore and Micrometer's no-arg `setValue()` reset path.

Changed files:

- `src/main/java/com/navneet/executespec/tool/WorkspaceContext.java`
- `src/main/java/com/navneet/executespec/tool/WorkspaceContextThreadLocalAccessor.java`
- `src/test/java/com/navneet/executespec/tool/WorkspaceContextThreadLocalAccessorTest.java`

Verification:

- `WorkspaceContextThreadLocalAccessorTest`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven at the thread-local accessor boundary.
- Not yet verified through an async WorkerAgent path that takes a Micrometer context snapshot while a worktree override is active.

### 35. `verifyBoot` and `runWorkspaceTests` are now absent from worker schema when disabled

Problem:

The tools/reviewer review found that `backgroundJob` and `readCodeOutline` used `FlagGatedTool` and disappeared from the model schema when disabled, while `verifyBoot` and `runWorkspaceTests` stayed bound and returned `available:false`. That reintroduced the exact schema-sensitive-provider risk called out in the DeepSeek/V217 rollback notes: a disabled tool can still change model behavior merely by being present.

Fix:

- `VerifyBootTool` now implements `FlagGatedTool`.
- `WorkspaceRunTestsTool` now implements `FlagGatedTool`.
- Kept the direct `execute(...)` disabled-envelope behavior as defense-in-depth for non-registry callers.
- Updated `ToolRegistry` / `FlagGatedTool` documentation so the implementor list matches runtime.
- Added registry-level off/on tests for both tools.

Changed files:

- `src/main/java/com/navneet/executespec/tool/VerifyBootTool.java`
- `src/main/java/com/navneet/executespec/tool/WorkspaceRunTestsTool.java`
- `src/main/java/com/navneet/executespec/tool/FlagGatedTool.java`
- `src/main/java/com/navneet/executespec/tool/ToolRegistry.java`
- `src/test/java/com/navneet/executespec/tool/ToolRegistryWorkerBindTest.java`

Verification:

- `ToolRegistryWorkerBindTest`
- `WorkspaceRunTestsToolTest`
- `VerifyBootToolTest`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven that both tools are absent from worker bindings when flags are off and present when flags are on.
- Direct execution fallback still works.
- Not yet proven through a real worker LLM request payload capture.

### 36. PR-comment dismiss now checks run ownership before mutation

Problem:

The review-surfaces review found that `PrCommentInboxController.dismiss(...)` called `service.dismiss(commentId)` before checking whether the comment belonged to the `{runId}` path. Since inbox ids are sequential, a caller with access to one run could enumerate ids and dismiss comments from another run while receiving a 404 afterward.

Fix:

- Added `PrCommentInboxService.dismissForRun(commentId, runId)`.
- Updated the controller to call the run-scoped mutation method instead of mutating then filtering.
- Added tests proving cross-run ids do not change row status or call `save(...)`.

Changed files:

- `src/main/java/com/navneet/executespec/prreview/inbox/PrCommentInboxService.java`
- `src/main/java/com/navneet/executespec/prreview/inbox/PrCommentInboxController.java`
- `src/test/java/com/navneet/executespec/prreview/inbox/PrCommentInboxServiceTest.java`

Verification:

- `PrCommentInboxServiceTest`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven at the service boundary.
- Not yet live-verified via authenticated HTTP with two runs/comments.

### 37. Code-pushing apply endpoints now require run write access

Problem:

The review-surfaces review found that two code-pushing apply endpoints used read visibility guards:

- `POST /api/runs/{runId}/apply`
- `POST /api/runs/{runId}/pr-comments/{commentId}/apply`

Both paths can run an apply worker and push commits to PR branches, so `requireRunAccess(...)` was too weak for org developers/viewers with read visibility. The PR-comment dismiss endpoint also mutates inbox state and was still using the read guard.

Fix:

- Upgraded run-chat apply to `RunAccessGuard.requireRunWriteAccess(...)`.
- Upgraded PR-comment apply and dismiss to `RunAccessGuard.requireRunWriteAccess(...)`.
- Added controller tests that assert the write guard is used and the read guard is not used.

Changed files:

- `src/main/java/com/navneet/executespec/api/controller/RunConversationController.java`
- `src/main/java/com/navneet/executespec/prreview/inbox/PrCommentInboxController.java`
- `src/test/java/com/navneet/executespec/api/controller/RunConversationControllerGuardTest.java`
- `src/test/java/com/navneet/executespec/prreview/inbox/PrCommentInboxControllerGuardTest.java`

Verification:

- `RunConversationControllerGuardTest`
- `PrCommentInboxControllerGuardTest`
- Existing `PrCommentInboxServiceTest`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven at controller guard-call boundary.
- Not yet live-verified through authenticated viewer/developer users attempting apply/dismiss.

### 38. PR-review and code-review LLM cost are no longer double-counted

Problem:

The review-surfaces review found that `LlmRouter.dispatchWithRetry(...)` already records each PR-review/code-review response into the synthetic context bucket (`pr-review-{id}` / `code-review-{id}`), but `PrReviewAgent` and `CodeReviewAgent` manually called `RunCostTracker.record(...)` again for the same `ChatResponse`. Services then snapshotted those buckets into DB rows, so review costs and tokens were inflated by roughly 2x.

Fix:

- Removed the duplicate manual `costTracker.record(...)` call from `PrReviewAgent`.
- Removed the duplicate manual `costTracker.record(...)` call from `CodeReviewAgent`.
- Removed the no-longer-needed agent constructor dependency on `RunCostTracker`.

Changed files:

- `src/main/java/com/navneet/executespec/prreview/PrReviewAgent.java`
- `src/main/java/com/navneet/executespec/codereview/CodeReviewAgent.java`
- `src/test/java/com/navneet/executespec/codereview/CodeReviewAgentParseTest.java`

Verification:

- `CodeReviewAgentParseTest`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Compile-proven that the duplicate direct recording path is gone.
- Existing service snapshot behavior remains unchanged.
- Not yet live-verified through an actual PR-review/code-review LLM call and persisted cost row.

### 39. Code-review issue severity ordering and Semgrep CRITICAL mapping fixed

Problem:

The review-surfaces review found two code-review surface issues:

- `CodeReviewIssueRepository.findByScanIdOrderBySeverityAsc...` sorted severity alphabetically, yielding `CRITICAL -> INFO -> MAJOR -> MINOR` in JSON responses.
- `SemgrepRunner` collapsed Semgrep `CRITICAL` findings into product `MAJOR`, so static-analysis CRITICAL autofix buckets were impossible.

Fix:

- Replaced derived severity ordering with JPQL `CASE` ordering: `CRITICAL -> MAJOR -> MINOR -> INFO -> unknown`.
- Kept the same repository method names so callers remain unchanged.
- Mapped Semgrep `CRITICAL` to product `CRITICAL`; Semgrep `ERROR` still maps to `MAJOR`.

Changed files:

- `src/main/java/com/navneet/executespec/codereview/repository/CodeReviewIssueRepository.java`
- `src/main/java/com/navneet/executespec/codereview/SemgrepRunner.java`
- `src/test/java/com/navneet/executespec/codereview/SemgrepRunnerTest.java`

Verification:

- `SemgrepRunnerTest`
- Backend compile passed.
- `git diff --check` passed.

Working status:

- Unit-proven for Semgrep severity mapping.
- Repository query syntax is compile-clean; full repository initialization will be checked during backend server startup.
- Not yet live-verified through a code-review JSON endpoint with mixed severities.

### 40. DeepSeek and Fireworks now have first-class BYOK key lanes

Problem:

The model-matrix review found that the code treated some providers as routable while the key-resolution hierarchy only covered Anthropic/OpenAI/Gemini plus org-only OpenRouter. Native DeepSeek and Fireworks could therefore be enum-routable but not consistently configurable at user/org level.

Fix:

- Added Flyway `V237__provider_key_lanes_deepseek_fireworks.sql`.
- Added encrypted `deepseek_api_key` and `fireworks_api_key` fields to `org` and `app_user`.
- Extended `ApiKeyResolver`, `RunKeyContext`, `RunKeyContextStore`, `RunKeyStore`, DTOs, controllers, and frontend settings APIs.
- Added IDE user API-key rows for DeepSeek and Fireworks.
- Added enterprise org-level OpenRouter/DeepSeek/Fireworks rows in both IDE and remaining Signal org settings surfaces.
- `AvailableProviderResolver` now considers system/user/org DeepSeek and Fireworks keys.

Working status:

- Unit/build-proven for key storage, key resolution, and UI type safety.
- Not live-proven through actual browser save/validate flows for DeepSeek/Fireworks keys.
- Fireworks live validation is blocked because no real Fireworks key was available in local config.

### 41. OpenRouter Chinese-model cost and latency routing corrected

Problem:

The aggregator review found two separate issues: Chinese OpenRouter models such as `deepseek-v4-flash` had literal `openrouter/model` pricing rows but cost lookup could fall back incorrectly, and OpenRouter default provider routing prioritizes price, which matched the MiMo latency failure mode from the handoff.

Fix:

- `RunCostTracker.priceCall(...)` now looks up the literal `(provider, model)` row first.
- OpenRouter falls back to owner-provider pricing only for legacy direct-provider pass-through models such as GPT/Claude/Gemini.
- Native DeepSeek unsuffixed ids fall back to the `-native` pricing row.
- `LlmRouter.callOpenRouter(...)` now sends `extraBody.provider.sort = "latency"`.
- `OpenAiNativeToolClient` supports per-request `extraBody`.

Verification:

- `RunCostTrackerCachePricingTest` covers OpenRouter literal Chinese pricing, legacy owner fallback, and native DeepSeek alias pricing.
- `LlmRouterTest.openRouterRequestsLatencySortedProviderRouting`
- Live tiny OpenRouter MiMo probe from local setup: default avg 10.7s; `provider.sort=throughput` avg 7.2s; `provider.sort=latency` avg 3.4s.

Working status:

- Unit-proven for request-body shape and pricing math.
- Live-proven at tiny scale that `sort=latency` materially improves MiMo request latency from the local setup.
- Not VM-proven from `us-east4`; that remains the next required measurement before claiming the production latency problem is solved.

### 42. Fireworks is now tier-routable but still needs live key validation

Problem:

The worktree already had a `FIREWORKS` enum and `callFireworks(...)` route, but no `model-matrix.providers.fireworks` row. A user/admin could make Fireworks available and then hit `ModelResolver: No model matrix config for provider`.

Fix:

- Added Fireworks provider defaults: PLANNING/WORKHORSE `deepseek-v4-pro-fw`, LIGHTWEIGHT `deepseek-v4-flash-fw`.
- Added Fireworks YAML model rows for `deepseek-v4-pro-fw`, `deepseek-v4-flash-fw`, `kimi-k2p6-fw`, and `glm-5.1-fw`.
- Replaced stale Fireworks mapper slugs with current Fireworks model-page slugs.
- Added `FireworksModelMapperTest`.
- Added Fireworks model labels and selectable model options in the org model matrix endpoint.
- Extended `ArchLlmDispatch` and `ProjectChatActionTools` for Fireworks.

Working status:

- Compile/unit-proven and no longer crashes at model-resolution time.
- Not live-proven through Fireworks API because no real Fireworks key was resolved locally; unauthenticated catalog access returns 403.
- Fireworks route should stay treated as beta until catalog smoke and latency A/B run with a real key.

## Verification Commands Run

All commands used `JAVA_HOME=/opt/homebrew/opt/openjdk@21`.

Passed:

```bash
mvn -Dtest=RunPersistenceWriterTest test -q
mvn -Dtest=ShellSandboxPolicyTest test -q
mvn -Dtest=WriteFileToolTest,EditFileToolTest test -q
mvn -Dtest=ShellSandboxPolicyTest,WriteFileToolTest,EditFileToolTest,RunTestsToolTest test -q
mvn -Dtest=RunPersistenceWriterTest,ShellSandboxPolicyTest,WriteFileToolTest,EditFileToolTest,RunTestsToolTest test -q
mvn -Dtest=RateLimitPolicyLevelTest,PaymentAuditControllerTest,DeploymentControllerTest,StandardsComplianceControllerTest test -q
mvn -Dtest=BillingServiceStripeTest test -q
mvn -Dtest=BillingServiceStripeTest,RunQuotaRefundListenerTest test -q
mvn -Dtest=AdminAnalyticsControllerTest test -q
mvn -Dtest=ProjectShareLinkServiceTest,ProjectInviteServiceTest test -q
mvn -Dtest=ContractDetectionServiceTest test -q
mvn -Dtest=FeatureFlagServiceTest test -q
mvn -Dtest=RateLimitPolicyLevelTest,RateLimitFilterTest test -q
mvn -Dtest=TierEnforcementServiceRunQuotaTest test -q
mvn -Dtest=BillingSubscriptionRepositoryQueryTest test -q
mvn -Dtest=RunTopupServiceTest test -q
mvn -Dtest=RunTopupServiceTest,BillingServiceStripeTest test -q
mvn -Dtest=OrgServiceSeatPolicyTest test -q
mvn -Dtest=InvoiceControllerTest test -q
mvn -Dtest=AccountingServiceTest test -q
mvn -Dtest=AccuracyAssessmentExtractKeywordsTest test -q
mvn -Dtest=RunResourceCleanerTest,JobRegistryTest test -q
mvn -Dtest=WorkspaceContextThreadLocalAccessorTest test -q
mvn -Dtest=ToolRegistryWorkerBindTest,WorkspaceRunTestsToolTest,VerifyBootToolTest test -q
mvn -Dtest=PrCommentInboxServiceTest test -q
mvn -Dtest=RunConversationControllerGuardTest,PrCommentInboxControllerGuardTest,PrCommentInboxServiceTest test -q
mvn -Dtest=CodeReviewAgentParseTest test -q
mvn -Dtest=SemgrepRunnerTest test -q
mvn -Dtest=RunPersistenceWriterTest,RunResourceCleanerTest,ShellSandboxPolicyTest,WriteFileToolTest,EditFileToolTest,RunTestsToolTest,WorkspaceContextThreadLocalAccessorTest,ToolRegistryWorkerBindTest,WorkspaceRunTestsToolTest,VerifyBootToolTest,JobRegistryTest,AccuracyAssessmentExtractKeywordsTest,RateLimitPolicyLevelTest,RateLimitFilterTest,PaymentAuditControllerTest,DeploymentControllerTest,StandardsComplianceControllerTest,BillingServiceStripeTest,RunQuotaRefundListenerTest,AdminAnalyticsControllerTest,ProjectShareLinkServiceTest,ProjectInviteServiceTest,ContractDetectionServiceTest,FeatureFlagServiceTest,TierEnforcementServiceRunQuotaTest,BillingSubscriptionRepositoryQueryTest,RunTopupServiceTest,OrgServiceSeatPolicyTest,InvoiceControllerTest,AccountingServiceTest,PrCommentInboxServiceTest,RunConversationControllerGuardTest,PrCommentInboxControllerGuardTest,CodeReviewAgentParseTest,SemgrepRunnerTest test -q
mvn -Dtest=RunPersistenceWriterTest,ShellSandboxPolicyTest,WriteFileToolTest,EditFileToolTest,RunTestsToolTest,RateLimitPolicyLevelTest,RateLimitFilterTest,PaymentAuditControllerTest,DeploymentControllerTest,StandardsComplianceControllerTest,BillingServiceStripeTest,RunQuotaRefundListenerTest,AdminAnalyticsControllerTest,ProjectShareLinkServiceTest,ProjectInviteServiceTest,ContractDetectionServiceTest,FeatureFlagServiceTest,TierEnforcementServiceRunQuotaTest,BillingSubscriptionRepositoryQueryTest,RunTopupServiceTest test -q
mvn -DskipTests compile -q
mvn -Dtest=RunKeyContextStoreTest,OrgServiceAiKeysTest,LlmRouterTest,RunCostTrackerCachePricingTest,FireworksModelMapperTest test -q
mvn -Dtest=FailoverOrderEnumAlignmentTest test -q
mvn -Dtest=RunControllerSecurityTest,RunPersistenceWriterTest,RunResourceCleanerTest,RunKeyContextStoreTest,OrgServiceAiKeysTest,OrgServiceSeatPolicyTest,TierEnforcementServiceRunQuotaTest,AccountingServiceTest,BillingServiceStripeTest,RunQuotaRefundListenerTest,RunTopupServiceTest,CodeReviewAgentParseTest,SemgrepRunnerTest,ContractDetectionServiceTest,RunCostTrackerCachePricingTest,RateLimitFilterTest,RateLimitPolicyLevelTest,AccuracyAssessmentExtractKeywordsTest,ProjectInviteServiceTest,ProjectShareLinkServiceTest,ShellSandboxPolicyTest,RunTestsToolTest,ToolRegistryWorkerBindTest,EditFileToolTest,WriteFileToolTest,WorkspaceContextThreadLocalAccessorTest,LlmRouterTest,FireworksModelMapperTest,FailoverOrderEnumAlignmentTest test -q
mvn spotless:check -q
mvn verify -q
npm --prefix frontend run build
npm --prefix frontend run format:check
npm --prefix frontend run lint
git diff --check
```

Frontend lint passed with the repo's existing warning-tolerant gate (`--max-warnings 9999`). It reported 8 unused-import warnings in `frontend/src/lib/api/core.ts` and `frontend/src/lib/api/deploy.ts`, with 0 errors.

Full Maven verify result: `BUILD SUCCESS`, `Tests run: 1699, Failures: 0, Errors: 0, Skipped: 0`.

Live/provider probes:

```text
OpenRouter catalog: HTTP 200; Chinese-model sample included deepseek/deepseek-v4-pro, deepseek/deepseek-v4-flash, xiaomi/mimo-v2.5-pro, moonshotai/kimi-k2.6, z-ai/glm-5.1.
DeepSeek catalog: HTTP 200; models included deepseek-v4-flash and deepseek-v4-pro.
Fireworks catalog: no real Fireworks key resolved locally; unauthenticated catalog request returned HTTP 403.
OpenRouter MiMo latency probe: default avg 10.7s; provider.sort=throughput avg 7.2s; provider.sort=latency avg 3.4s.
```

## Server Verification

Final runtime verification was refreshed after the latest code changes. The stale backend process was stopped and a fresh local-profile backend was started before these checks.

Backend:

- Started with local profile:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -Dspring-boot.run.profiles=local
```

- URL: `http://localhost:8080`
- Health check:

```bash
curl http://localhost:8080/actuator/health
```

- Result: `200`, body `{"status":"UP"}`
- Final restart verification: `curl -sS http://localhost:8080/actuator/health` returned `{"status":"UP"}` after the backend restarted from the current worktree.
- Static root verification: `curl -i http://127.0.0.1:8080/` returned `200` and served `text/html`.
- Auth boundary smoke: unauthenticated `GET /api/models/available` returned `401` with `Authentication required`, confirming the server was not accidentally opened by the security changes.

Frontend:

- Started with:

```bash
npm --prefix frontend run dev -- --host 127.0.0.1
```

- URL: `http://127.0.0.1:5173/`
- HTTP smoke result: `200`, Vite index served.
- Process check: `lsof -nP -iTCP:5173 -sTCP:LISTEN` showed a `node` process listening on `127.0.0.1:5173`.
- Final HTTP verification: `curl -sS http://127.0.0.1:5173/` returned the app shell with `<title>ExecuteSpec</title>` and `<div id="root"></div>`.
- Browser smoke:
  - Opened frontend in the in-app browser.
  - Redirected to `/login`.
  - Rendered login surface with page title `ExecuteSpec`.
  - Browser console errors during initial load: none observed.

Backend startup warnings observed:

- Local Flyway warning: PostgreSQL 18.1 is newer than the Flyway-tested 17 line.
- Local GCS/archive warning: application default credentials failed with `invalid_grant`, so workspace archiving is disabled locally.
- Local encryption warning: no credential encryption key configured; ephemeral key generated.
- Local Cloud Run/gcloud warning: `gcloud auth application-default login` may need reauth for preview/deploy features.
- Spring async warning: multiple `TaskExecutor` beans exist and no unique default executor named `taskExecutor`.

Impact:

These warnings did not block backend boot or the health check. They do matter for deploy/preview/archive surfaces, but they are not blockers for this local verification batch.

## What Is Working Now

- Stale status writes no longer clobber `CANCELLED` or `PAUSED` at the persistence-writer boundary.
- Active status writes still persist and broadcast.
- Lifecycle completion paths can pass explicit `statusReason`.
- Patch-mode now uses central termination instead of manual divergent status/cost/event writes.
- Auto-push decisions after patch-mode finding runs now inspect persisted terminal status.
- `terminate(...)` handles null `startedAt` safely.
- Shell newline command chaining is inspected like semicolon/pipe chaining.
- Quoted multiline shell arguments are not broken by the newline split.
- `writeFile` blocks suspicious generated content before file creation.
- `editFile` blocks suspicious post-edit content and preserves the original file.
- `RunTestsTool` avoids host/non-login-container shell interpolation for pytest test IDs.
- TEAM tier no longer falls back to FREE endpoint rate limits.
- Payment history rejects cross-org reads for non-super users.
- Deployment list/live endpoints use org-scoped reads for non-super users.
- Standards report refuses an `orgId` that does not own the project.
- Forked runs now use the same monthly quota increment path as normal submit.
- Stripe checkout completion rewrites local subscriptions from checkout-session id to subscription id.
- Stripe user cancellation calls the period-end cancellation gateway method.
- Paid subscription upgrades no longer cancel/refund the old subscription before the new checkout is paid.
- The legacy billing seat endpoint no longer mutates local seat counts without gateway sync.
- Failed-run quota refunds match the actual one-run monthly counter charge instead of estimated credits.
- Solo FREE users at quota can spend valid top-up runs instead of being rejected before bonus lookup.
- `CANCELLING_AT_PERIOD_END` subscriptions stay visible as active and are eligible for period-end downgrade.
- Top-up payment verification rejects orders owned by another user before mutation.
- Top-up client verify and Razorpay webhook processing now share an atomic activation update, so only one path emits side effects.
- TEAM org seat adjustment now accepts one seat and no longer shows stale "min 2" copy.
- Invoice resend emails the invoice owner instead of the calling admin.
- Admin credit-note API rejects negative and over-invoice amounts.
- GSTR-1 export handles legacy invoices with null GST rate fields.
- Purchased top-ups retain their package id for invoice generation.
- Reviewer missing-requirement grep verification now emits grep-compatible literal search patterns.
- Run-end cleanup now calls `JobRegistry.cleanup(runId)` for background jobs.
- WorkspaceContext thread-local propagation now preserves the task worktree override.
- `verifyBoot` and `runWorkspaceTests` are removed from worker tool schemas while their flags are off.
- PR-comment dismiss no longer mutates a comment before proving it belongs to the requested run.
- Apply endpoints that can push code now require run write access.
- PR-review and code-review LLM costs are no longer manually recorded twice.
- Code-review JSON issue ordering now uses canonical severity rank, and Semgrep CRITICAL stays CRITICAL.
- Analytics accepts lowercase org/user entity types and the legacy admin path is reachable to authenticated org-scoped callers.
- Invite and share-link revoke operations reject UUIDs from another project.
- Contract detection no longer pre-escapes multiline inline contract content.
- Feature flag reloads publish a complete cache snapshot instead of clearing the live cache.
- IP-scoped rate limits now use the configured trusted-proxy position in `X-Forwarded-For`.
- Backend and frontend both start locally and serve expected smoke endpoints.

## What Is Not Yet Proven

- A real cancellation race against an active worker thread has not been executed.
- A real pause/resume completion has not been executed.
- A real retry-task terminal path has not been executed.
- A real patch-mode finding run with SCM auto-push has not been executed.
- A full WorkerAgent run that attempts malicious generated content has not been executed.
- A real SWE-bench cleanroom `runTests` invocation has not been executed.
- A real forked run has not been submitted to observe quota increment plus later refund behavior.
- The new deployment JPQL queries have not been exercised against a live database row set.
- Stripe fixes have not been verified against real Stripe checkout/webhook traffic.
- Upgrade-flow fixes have not been verified against real Stripe/Razorpay sandbox checkout abandonment and webhook ordering.
- The legacy billing seat endpoint has not been exercised through HTTP after the fail-closed change.
- FREE top-up usage has not been verified through a live purchase plus run submission.
- SUNSETTING period-end downgrade has not been verified through a real DB row plus scheduled job.
- Top-up activation has not been database-concurrency-proven with two real transactions racing on PostgreSQL.
- TEAM one-seat adjustment has not been live-verified through an authenticated HTTP request.
- Invoice resend has not been live-verified through an authenticated admin HTTP request.
- Credit-note bounds have not been live-verified through an authenticated finance/admin HTTP request.
- GSTR-1 export has not been checked against a real legacy invoice export file.
- Top-up package-reference flow has not been verified through a real purchase-to-invoice path.
- Reviewer grep demotion has not been verified through a real workspace-backed report assessment.
- Background job cleanup has not been verified through a live run that starts and forgets a background job.
- WorkspaceContext propagation has not been verified through a real async worker path.
- `verifyBoot` / `runWorkspaceTests` schema absence has not been verified through a captured live LLM request payload.
- PR-comment dismiss has not been live-verified through authenticated HTTP with cross-run comment ids.
- Apply write-guard behavior has not been live-verified through authenticated viewer/developer users.
- PR-review/code-review cost accounting has not been live-verified against a real LLM call and persisted review row.
- Code-review issue ordering has not been live-verified through an endpoint backed by mixed-severity rows.
- Billing analytics has not been verified with a real authenticated non-super browser session.
- Contract inline content has not been verified through a live PostgreSQL JSONB round trip.
- Rate-limit IP extraction has not been verified against real Cloudflare/GCP forwarded header chains.
- Authenticated model-matrix read/write APIs have not been live-verified through a browser session because this local profile has no active login/dev-login session available.

These gaps are not theoretical nice-to-haves. They are the next proof boundary before claiming production readiness for the orchestration fixes.

## Files Changed By This Fix Batch

Code:

- `src/main/java/com/navneet/executespec/integration/RunControlPort.java`
- `src/main/java/com/navneet/executespec/integration/RunLifecycleService.java`
- `src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`
- `src/main/java/com/navneet/executespec/integration/RunPersistenceWriter.java`
- `src/main/java/com/navneet/executespec/tool/ShellSandboxPolicy.java`
- `src/main/java/com/navneet/executespec/tool/WriteFileTool.java`
- `src/main/java/com/navneet/executespec/tool/EditFileTool.java`
- `src/main/java/com/navneet/executespec/tool/RunTestsTool.java`
- `src/main/java/com/navneet/executespec/ratelimit/RateLimitPolicy.java`
- `src/main/java/com/navneet/executespec/ratelimit/RateLimitProperties.java`
- `src/main/java/com/navneet/executespec/ratelimit/RateLimitFilter.java`
- `src/main/java/com/navneet/executespec/billing/controller/PaymentAuditController.java`
- `src/main/java/com/navneet/executespec/api/controller/DeploymentController.java`
- `src/main/java/com/navneet/executespec/state/repository/DeploymentRepository.java`
- `src/main/java/com/navneet/executespec/standards/report/StandardsComplianceController.java`
- `src/main/java/com/navneet/executespec/api/controller/RunController.java`
- `src/main/java/com/navneet/executespec/billing/service/BillingService.java`
- `src/main/java/com/navneet/executespec/billing/service/RunQuotaRefundListener.java`
- `src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java`
- `src/main/java/com/navneet/executespec/api/controller/AdminAnalyticsController.java`
- `src/main/java/com/navneet/executespec/billing/service/AnalyticsService.java`
- `src/main/java/com/navneet/executespec/sharing/service/ProjectInviteService.java`
- `src/main/java/com/navneet/executespec/sharing/controller/ProjectInviteController.java`
- `src/main/java/com/navneet/executespec/sharing/service/ProjectShareLinkService.java`
- `src/main/java/com/navneet/executespec/sharing/controller/ProjectShareLinkController.java`
- `src/main/java/com/navneet/executespec/contracts/detection/ContractDetectionService.java`
- `src/main/java/com/navneet/executespec/config/featureflag/FeatureFlagService.java`
- `src/main/java/com/navneet/executespec/reviewer/AccuracyAssessmentService.java`
- `src/main/java/com/navneet/executespec/integration/RunResourceCleaner.java`
- `src/main/java/com/navneet/executespec/tool/WorkspaceContext.java`
- `src/main/java/com/navneet/executespec/tool/WorkspaceContextThreadLocalAccessor.java`
- `src/main/java/com/navneet/executespec/tool/VerifyBootTool.java`
- `src/main/java/com/navneet/executespec/tool/WorkspaceRunTestsTool.java`
- `src/main/java/com/navneet/executespec/tool/FlagGatedTool.java`
- `src/main/java/com/navneet/executespec/tool/ToolRegistry.java`
- `src/main/java/com/navneet/executespec/prreview/inbox/PrCommentInboxService.java`
- `src/main/java/com/navneet/executespec/prreview/inbox/PrCommentInboxController.java`
- `src/main/java/com/navneet/executespec/api/controller/RunConversationController.java`
- `src/main/java/com/navneet/executespec/prreview/PrReviewAgent.java`
- `src/main/java/com/navneet/executespec/codereview/CodeReviewAgent.java`
- `src/main/java/com/navneet/executespec/codereview/repository/CodeReviewIssueRepository.java`
- `src/main/java/com/navneet/executespec/codereview/SemgrepRunner.java`
- `src/main/java/com/navneet/executespec/agent/ApiKeyResolver.java`
- `src/main/java/com/navneet/executespec/agent/LlmRouter.java`
- `src/main/java/com/navneet/executespec/agent/OpenAiNativeToolClient.java`
- `src/main/java/com/navneet/executespec/agent/ResolvedKeys.java`
- `src/main/java/com/navneet/executespec/agent/RunKeyContext.java`
- `src/main/java/com/navneet/executespec/agent/RunKeyContextStore.java`
- `src/main/java/com/navneet/executespec/router/AvailableProviderResolver.java`
- `src/main/java/com/navneet/executespec/router/FireworksModelMapper.java`
- `src/main/java/com/navneet/executespec/cost/RunCostTracker.java`
- `src/main/java/com/navneet/executespec/auth/controller/AuthController.java`
- `src/main/java/com/navneet/executespec/auth/controller/OrgController.java`
- `src/main/java/com/navneet/executespec/auth/service/OrgService.java`
- `src/main/java/com/navneet/executespec/auth/entity/AppUser.java`
- `src/main/java/com/navneet/executespec/auth/entity/Org.java`
- `src/main/java/com/navneet/executespec/api/dto/RunRequest.java`
- `src/main/java/com/navneet/executespec/auth/dto/CreateProjectRequest.java`
- `src/main/java/com/navneet/executespec/codeintel/ArchLlmDispatch.java`
- `src/main/java/com/navneet/executespec/specchat/ProjectChatActionTools.java`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V237__provider_key_lanes_deepseek_fireworks.sql`
- `frontend/src/ide/IdeOrg.tsx`
- `frontend/src/signal/pages/LegalPages.tsx`
- `frontend/src/lib/api/auth.ts`
- `frontend/src/lib/api/core.ts`
- `frontend/src/lib/api/runs.ts`

Tests:

- `src/test/java/com/navneet/executespec/integration/RunPersistenceWriterTest.java`
- `src/test/java/com/navneet/executespec/tool/ShellSandboxPolicyTest.java`
- `src/test/java/com/navneet/executespec/tool/WriteFileToolTest.java`
- `src/test/java/com/navneet/executespec/tool/EditFileToolTest.java`
- `src/test/java/com/navneet/executespec/tool/RunTestsToolTest.java`
- `src/test/java/com/navneet/executespec/ratelimit/RateLimitPolicyLevelTest.java`
- `src/test/java/com/navneet/executespec/ratelimit/RateLimitFilterTest.java`
- `src/test/java/com/navneet/executespec/billing/controller/PaymentAuditControllerTest.java`
- `src/test/java/com/navneet/executespec/api/controller/DeploymentControllerTest.java`
- `src/test/java/com/navneet/executespec/standards/report/StandardsComplianceControllerTest.java`
- `src/test/java/com/navneet/executespec/billing/service/BillingServiceStripeTest.java`
- `src/test/java/com/navneet/executespec/billing/service/RunQuotaRefundListenerTest.java`
- `src/test/java/com/navneet/executespec/api/controller/AdminAnalyticsControllerTest.java`
- `src/test/java/com/navneet/executespec/sharing/ProjectInviteServiceTest.java`
- `src/test/java/com/navneet/executespec/sharing/ProjectShareLinkServiceTest.java`
- `src/test/java/com/navneet/executespec/contracts/detection/ContractDetectionServiceTest.java`
- `src/test/java/com/navneet/executespec/config/featureflag/FeatureFlagServiceTest.java`
- `src/test/java/com/navneet/executespec/reviewer/AccuracyAssessmentExtractKeywordsTest.java`
- `src/test/java/com/navneet/executespec/integration/RunResourceCleanerTest.java`
- `src/test/java/com/navneet/executespec/tool/WorkspaceContextThreadLocalAccessorTest.java`
- `src/test/java/com/navneet/executespec/tool/ToolRegistryWorkerBindTest.java`
- `src/test/java/com/navneet/executespec/prreview/inbox/PrCommentInboxServiceTest.java`
- `src/test/java/com/navneet/executespec/api/controller/RunConversationControllerGuardTest.java`
- `src/test/java/com/navneet/executespec/prreview/inbox/PrCommentInboxControllerGuardTest.java`
- `src/test/java/com/navneet/executespec/codereview/CodeReviewAgentParseTest.java`
- `src/test/java/com/navneet/executespec/codereview/SemgrepRunnerTest.java`
- `src/test/java/com/navneet/executespec/agent/LlmRouterTest.java`
- `src/test/java/com/navneet/executespec/agent/RunKeyContextStoreTest.java`
- `src/test/java/com/navneet/executespec/auth/service/OrgServiceAiKeysTest.java`
- `src/test/java/com/navneet/executespec/cost/RunCostTrackerCachePricingTest.java`
- `src/test/java/com/navneet/executespec/router/FireworksModelMapperTest.java`
- `src/test/java/com/navneet/executespec/arch/FailoverOrderEnumAlignmentTest.java`

Plans/reports:

- `docs/plans/review-driven-execution-plan-2026-06-10.md`
- `docs/plans/benchmark-server-creation-deployment-2026-06-10.md`
- `docs/plans/review-execution-fix-report-2026-06-10.md`

## Recommended Next Execution

1. Build a controlled local race test for cancel/pause while a fake worker is still unwinding.
2. Add a patch-mode integration test that asserts finalization uses central `terminate(...)` and never auto-pushes if the persisted run was cancelled.
3. Execute a no-spend authenticated local run path using stubs/fakes, not real providers, to validate SSE closure and terminal events.
4. Address the remaining P0 billing/payment integrity items from `review/checkpoints/be-billing.md`.
5. Address tenant isolation review items before broad UI/UX work.
6. Only after those P0s, resume the model matrix/provider work.
