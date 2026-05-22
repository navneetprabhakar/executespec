# Earlier-chrome → current (Signal + IDE) feature-gap audit

> **Date:** 2026-05-22 · **Branch:** `feature/openrouter-provider-2026-05-21`
> **Question (founder):** "A lot of gaps between earlier chrome and current Signal + IDE" — features that existed in the older V2 sidebar / V3 top-bar UI but are missing or thinner in current Signal + IDE.
> **Method:** the earlier chromes were physically deleted from the working tree in commit `5f5e4aaa` ("chore(ui): delete legacy v2/v3/v4 chrome"). This audit recovers the **last commit where every chrome coexisted** — `d0e643ec` (parent of the deletion) — and **diffs the actual source** of `components/`, `pages/`, `v3/`, `v4/` against the live `signal/` + `ide/` working tree. The signal is each chrome's set of `lib/api.ts` calls (the strongest cross-chrome feature fingerprint), corroborated by reading the rich components directly.

> **Relationship to the existing audit:** `docs/audit/feature-gap-comparison.md` (2026-05-22) compared **Backend vs Signal vs IDE** and explicitly noted it *could not* diff the earlier chrome because that source was "already gutted to redirect stubs." This audit closes that gap: it recovers the earlier-chrome source from git and confirms, from real UI code, which "dark backend" capabilities **had a working, feature-rich UI** in the earlier chrome (vs. backend-only features that never had one). Findings here are consistent with — and sharpen — that document's Bucket A / Bucket B.

---

## TL;DR — the most valuable things the earlier chrome had that current Signal + IDE lack

Ranked by user value × confidence that real UI once existed (verified from recovered source).

1. **PR Review + Code Review submit & autofix** (`prreview/`, `codereview/`). Earlier chrome had `PrReviewButton` (submit a PR for AI review → verdict → **"Autofix N comments"**) and `CodeQualityPanel` (whole-repo scan → severity chips → **per-severity autofix dispatch**). These are **2 of the 4 customer-facing autofix surfaces** in the product spec (CLAUDE.md). Current chrome wires **none** of `submitPrReview`/`getPrReview`/`dispatchPrReviewAutofix`/`submitCodeReview`/`getCodeReview`/`dispatchCodeReviewAutofix`/`getLatestCodeReviewForRepo`/`listCodeReviewsForProject`/`codeReviewReportDownloadUrl`. (Signal's `PrCommentInbox` is a *different* feature — it surfaces inbound GitHub PR comments for apply-as-fix; it is **not** the agent PR-review/scan flow.)

2. **The V3 multi-solution Execution lane** (`/api/executions/*`, `execution-api.ts`, `useExecutionStore`, `useExecutionSse`). A complete parallel pipeline: `ExecutionSetup → ExecutionAnalysis → ArchReviewGate → ExecutionPlanReview → ExecutionMonitor → CancellationPanel → ExecutionComplete → ExecutionReportPanel`, driving arch-review → plan → **candidate-solution decide** → per-solution runs over its own SSE stream. Current `App.tsx` actively *redirects* `/projects/:id/executions/*` back to the project page; the entire `executionApi` object (create / triggerAnalysis / approveArch / generatePlan / approvePlan / **decide** / listSolutionRuns / getReport / abort / resume / listScaffolds) is dark in both chromes.

3. **Live Preview lifecycle** (`PreviewTab`, 1053 LOC). Earlier chrome embedded the running app in an `<iframe>` with a **responsive device frame** (`MobilePreviewPanel`), a **build/runtime console** (`ConsolePanel` with log filtering), and full lifecycle controls: `getPreviewStatus` (poll), `getPreviewLogs`, `rebuildPreview`, `stopPreview`, `analyzePreview`, `execPreviewCommand`. Signal/IDE only do fire-and-forget `startPreview` — no embedded preview, no logs, no stop/rebuild.

4. **Deploy wizard (analyze → configure → confirm)** (`DeployWizardModal`). Multi-step: select run → GCP credentials + required-permission check (`getRequiredPermissions`) → **`analyzeForDeploy`** (workspace inspection, detected build/Dockerfile) → configure region/env → **`confirmDeploy`**. Current chrome has one-click `createDeployment({runId})` only; `analyzeForDeploy`/`confirmDeploy`/`getDeploymentDetail`/`engineRollback`/`engineDestroy` are unused. (Note: `getDeployments`/`rollbackDeployment`/`deleteDeployment` list-and-manage and the GCP-credential CRUD `storeGcpCredential`/`validateGcpCredential`/`deleteGcpCredential` **do** survive in Signal.)

5. **Library + Migrations are live in earlier chrome, static stubs in Signal.** V3 `LibraryPage`/`LibraryDetailPage` called `listLibrary`/`getLibraryFacets`/`instantiateLibrarySolution`/`resolveLibraryVariant` (browse readymade solutions by facet, instantiate into a run). V3 `MigrationsPage`/`MigrationDetailPage` called `listMigrations`/`migrationDryRun`/`migrationApply` (recipe slot-filling + Phase-1 dry-run + apply). **Signal's `/library` + `/migrations` render hardcoded arrays (`LIBRARY_SOLUTIONS`, a fixed "Stripe checkout" detail) and call no API.** IDE has neither.

6. **Accounting / GST / invoicing surface** (`AccountingPage`, `InvoicesPage`). `getGstSummary`, `getTrialBalance`, `getGstr1ExportUrl`, `getAllInvoices`, `downloadInvoicePdf`, `resendInvoice` — a complete finance/GST UI. Dark in both current chromes.

7. **Run power-ops that didn't come back in Wave A1.** Wave A1 restored fork / archive / unarchive / soft-delete. Still gone from both: **pause / resume** (`pauseRun`/`resumeRun` — were on `RunDetail` + `InlineRunDetail`), **iterate-as-new-run** (`iterateRun`, and `applyToRun` "apply this suggestion to the workspace" from `RunConversation`), **move-run-to-project** (`updateRunProject`).

8. **Full admin console** (`pages/admin/*` — ~50 functions). User management (`adminListUsers`/`adminUpdateUserRole`/`adminUpdateUserStatus`/`adminUpdateUserTier`/`adminGetUser`), org management (`adminUpdateOrgStatus`/`adminUpdateOrgTier`/`adminUpdateOrgRunLimit`/`adminUpdateOrgApiKeys`), grant-runs (`adminGrantUserRuns`/`adminGrantOrgRuns`), escalated-invite approval (`adminListEscalated`/`adminApproveInvite`/`adminRejectInvite`/`adminInviteUser`), top-up-package CRUD, payment audit + CSV (`adminListPaymentAudit`/`adminExportPaymentAuditCsv`/`verifyAuditChain`), webhook-events log, tier config (`adminGetTierConfig`/`adminUpdateTierConfig`/`updateTierLifecycle`), model-pricing edit (`updateModelPricing`), usage-report trigger (`adminTriggerUsageReport`), emergency `haltAllActiveRuns`, member analytics + billing analytics (`getAnalyticsMembers`/`getAnalyticsBilling`), seat adjust (`adjustOrgSeats`). Current IDE Admin = activation-funnel + tier-lifecycle + feature-flag list only; Signal Admin is similarly thin.

9. **Project-workbench panels lost or thinned:** **PostMergeBar** (`getPostMergeBar`/`dismissPostMergeBar` — recent-merge banner above the dashboard), **Org Standards CRUD** (`createOrgStandard`/`updateOrgStandard`/`deleteOrgStandard`), **Contract-drift workflow** (`dismissContractDrift`/`refreshContractFromSource` — Signal has check/rescan but not dismiss/refresh-from-source), **ADR detail** (`getProjectAdr` — Signal lists ADRs but can't open one), **project score-trend chart** (`ProjectScoreTrend` / `getProjectScores`), **OpenAPI viewer** (`getOpenApiSpec`/`probeApiDocs`), **per-PR snapshot** (`getPullRequestSnapshot`/`getRunPrs` — Signal refreshes PRs at project level but lacks the per-PR CI/merge snapshot view).

10. **Integration management depth** (`IntegrationsPage`). Slack channel mapping/test (`listSlackChannels`/`sendSlackTestMessage`), Jira/Confluence/Bitbucket validate+settings (`validateOrgJira`/`validateOrgConfluence`/`validateBitbucket`/`updateOrgJiraSettings`/`updateOrgConfluenceSettings`/`updateGithubSettings`/`updateOrgKeySourceSettings`), per-integration run history (`getIntegrationRuns`). Current chrome has connect/status only.

---

## How the chromes line up

| | Earlier chrome (`d0e643ec`, pre-deletion) | Current (HEAD) |
| --- | --- | --- |
| Chromes present | V2 sidebar (`components/`+`pages/`), V3 top-bar (`v3/`), V4 (`v4/`), Signal (`signal/`) | Signal (`signal/`) + IDE (`ide/`) |
| `lib/api.ts` fns called (union) | **~212** | **144** (Signal 142 · IDE 56) |
| `lib/api.ts` fns called by earlier chrome but **nowhere** in Signal+IDE | — | **111** (full list in the appendix) |

**Key framing:** of those 111 orphaned functions, **all but a tiny v3-specific handful are still live exports in the current `lib/api.ts`** (the API client + backend survive). So these are overwhelmingly *UI losses*, not capability removals — the earlier chrome reached features the current UI simply no longer surfaces.

**IDE is the thinner of the two current chromes** — it calls 56 functions vs Signal's 142. The IDE→Signal regression (86 functions) is already inventoried as "Bucket B" in `feature-gap-comparison.md`; this report focuses on what *both* current chromes lost relative to the earlier chrome.

---

## Reality check: which earlier-chrome components were real vs stubs

Per the task's caution. The earlier chrome was **not** gutted at `d0e643ec` — it was fully intact (the gutting happened later, in `5f5e4aaa`). Component sizes confirm richness:

| Component | LOC | Real / stub |
| --- | --- | --- |
| `components/runs/RunDetailTabs.tsx` | 2791 | Real — 7-tab run detail (Overview/Plan/Timeline/Live Log/Code/Chat/Preview) |
| `components/runs/RunDetail.tsx` | 1221 | Real |
| `components/runs/PreviewTab.tsx` | 1053 | Real — iframe + console + device frame + lifecycle |
| `components/runs/NewRunModal.tsx` | 990 | Real — provider/model, executionMode, tddMode, costCap, figmaUrl, repo scoping, EstimateChip |
| `components/runs/RunConversation.tsx` | 968 | Real — run chat + iterate + apply-to-run |
| `components/project/PulseTab.tsx` | 862 | Real |
| `components/project/ProjectChatPanel.tsx` | 782 | Real |
| `components/project/InlineRunDetail.tsx` | 707 | Real |
| `components/runs/ValidationReportPanel.tsx` | 706 | Real — ScoreRing + 6-dim ScoreCard + issues (reviewer vs advisory) + RunStamp + PDF/share/regenerate |
| `components/project/ProjectPrRollup.tsx` | 643 | Real |
| `components/runs/LiveLog.tsx` | 651 | Real |
| `components/project/ArchitectureViewer.tsx` | 514 | Real |
| `components/project/CodeQualityPanel.tsx` | 469 | Real — code-review scan + per-severity autofix |
| `components/execution/ExecutionSetup.tsx` | 440 | Real — multi-solution lane |

The only effectively-empty surfaces are tiny presentational helpers (`StackBadge` 17 LOC, `ConflictWarning` 22 LOC, etc.). **No major earlier-chrome feature component was a stub.**

---

## Gap table

**Legend** — In Signal / In IDE:
`yes` = equivalent feature present & wired · `partial` = present but materially thinner · `no` = absent · `renamed` = same capability under a new name (not a loss).

### A. Run detail / run lifecycle

| Feature | Earlier-chrome location | In Signal? | In IDE? | Notes |
| --- | --- | --- | --- | --- |
| Run detail tabs (Overview/Plan/Timeline/Code/Cost/PR/Validation/Tests/Chat) | `runs/RunDetailTabs.tsx` | **yes** | partial | Signal `ResultPage` has full tab parity; IDE `IdeRunResult` lacks Timeline + thinner Code (no real diff on MODIFIED). |
| Validation report (ScoreRing, 6-dim ScoreCard, issue severity grid, reviewer-vs-advisory split, checks-passed, PDF/share/regenerate) | `runs/ValidationReportPanel.tsx`, `ScoreCard`, `ScoreRing`, `SignalBreakdown`, `VerdictPill` | **yes** | partial | Signal reuses ScoreCard + verdict + share/PDF/`retryValidationReport`. IDE thinner. |
| **RunStamp** (shareable 1-line run stamp + embed) | `project/RunStamp.tsx` (in report) | no | no | `getSharedReport`/share path survives but the embeddable run-stamp widget is gone. |
| Live execution view (SSE log, cancel, cost bar) | `runs/LiveLog.tsx`, `execution/ExecutionMonitor.tsx` | **yes** | partial | Signal `LivePage` (SSE + `cancelRun` + `CostProgressBar`). |
| **Live cost poll** during run (`getLiveCost` `/cost`) | `RunDetailTabs` (5s poll) | partial | partial | Current derives cost from report/SSE; the dedicated live `/cost` poll endpoint is unused. |
| Run chat | `runs/RunConversation.tsx` | **yes** | partial | Signal uses `conversationChat`/`sendRunChatMessage`. |
| **Apply suggestion to run workspace** (`applyToRun`) | `runs/RunConversation.tsx` | **no** | no | Chat could apply a suggested change to the existing run. Gone. |
| **Iterate-as-new-run** (`iterateRun`) | `runs/RunConversation.tsx` | **no** | no | Follow-up prompt spawning a new run from chat. Gone. |
| Fork / archive / unarchive / soft-delete | `runs/RunDetail.tsx`, `InlineRunDetail.tsx` | **yes** | yes | **Restored in Wave A1.** |
| **Pause / resume run** (`pauseRun`/`resumeRun`) | `runs/RunDetail.tsx`, `InlineRunDetail.tsx` | **no** | no | Flag-gated (`v2.pause.enabled`) but UI existed. Gone. |
| **Move run to project** (`updateRunProject`) | `InlineRunDetail.tsx` | **no** | no | Gone. |
| Retry failed task | report header + run toolbar | **yes** | yes | `retryTask` wired. |
| **OpenAPI spec viewer** (`getOpenApiSpec`/`probeApiDocs`) | run detail | **no** | no | `/runs/{id}/openapi.yaml` viewer. Gone. |

### B. Preview & deploy

| Feature | Earlier-chrome location | In Signal? | In IDE? | Notes |
| --- | --- | --- | --- | --- |
| **Embedded live preview** (iframe + device frame + console + log filter) | `runs/PreviewTab.tsx`, `ConsolePanel`, `MobilePreviewPanel` | **no** | no | Signal only fires `startPreview`. No iframe, no `getPreviewStatus`/`getPreviewLogs`. |
| **Preview lifecycle** (`stopPreview`/`rebuildPreview`/`analyzePreview`/`execPreviewCommand`) | `runs/PreviewTab.tsx` | **no** | no | Gone. |
| One-click permanent deploy (`createDeployment`) | (run detail) | **yes** | yes | Present in both — `ResultPage` + `IdeRunResult`. |
| Deployments list / rollback / delete | `pages/DeploymentsPage.tsx` | **yes** | partial | Signal `DeploymentsPage` (`getDeployments`/`rollbackDeployment`/`deleteDeployment`); IDE thin. |
| **Deploy wizard** (`analyzeForDeploy` → configure → `confirmDeploy`, `getRequiredPermissions`, `getDeploymentDetail`, `engineRollback`/`engineDestroy`) | `deployments/DeployWizardModal.tsx` | **no** | no | Replaced by one-click; analyze/configure/confirm step gone. |
| GCP credential CRUD | (deploy) | **yes** | partial | Signal `GcpCredentialPanel` wires `storeGcpCredential`/`validateGcpCredential`/`deleteGcpCredential`. |

### C. PR Review / Code Review (2 of the 4 autofix surfaces)

| Feature | Earlier-chrome location | In Signal? | In IDE? | Notes |
| --- | --- | --- | --- | --- |
| **Submit PR for AI review + verdict** (`submitPrReview`/`getPrReview`/`listPrReviewsForPr`) | `project/PrReviewButton.tsx`, `v3/CodeReviewPanel.tsx` | **no** | no | Gone. |
| **PR Review autofix** (`dispatchPrReviewAutofix` — autofix N comments) | `project/PrReviewButton.tsx` | **no** | no | Autofix Phase 1. Gone. |
| **Whole-repo Code Review scan** (`submitCodeReview`/`getCodeReview`/`getLatestCodeReviewForRepo`/`listCodeReviewsForProject`/`codeReviewReportDownloadUrl`) | `project/CodeQualityPanel.tsx` | **no** | no | Gone. |
| **Code Review autofix per severity** (`dispatchCodeReviewAutofix`) | `project/CodeQualityPanel.tsx` | **no** | no | Autofix Phase 4. Gone. |
| Inbound GitHub PR-comment inbox (apply-as-fix) | — (new in Signal) | yes | yes | `PrCommentInbox` — a *different*, newer feature (`getPrComments`/`applyPrComment`/`dismissPrComment`). Restored Wave A1. |
| Tech-debt fix (autofix Phase 2) | `project/TechDebtPanel.tsx` | **yes** | no | Signal `ProjectCodePage` wires `getTechDebt`/`dispatchTechDebtFix`; IDE missing. |

### D. Project workbench

| Feature | Earlier-chrome location | In Signal? | In IDE? | Notes |
| --- | --- | --- | --- | --- |
| Pulse / dashboard (last-run hero, score, workspace-health rollup) | `project/PulseTab.tsx`, `WorkspaceHealthPanel.tsx` | **yes** | partial | Signal `ProjectPulsePage` (`getProjectDashboard`/`getWorkspaceHealth`). |
| Architecture viewer + versions + diff + staleness | `project/ArchitectureViewer.tsx` | **yes** | partial→no | Signal `ProjectArchitecturePage` (full). IDE renders current doc only. |
| **Architecture annotate** (`annotateArchitecture`) | `project/ArchitectureViewer.tsx` | **no** | no | Inline annotation of arch doc. Gone. |
| Contracts: coverage + drift check/rescan | `project/ContractCoveragePanel.tsx`, `ContractDriftModal.tsx` | partial | no | Signal has `getContractCoverage`/`rescanContractCoverage`/`checkProjectDrift`/`checkRepoDrift`. |
| **Contract drift dismiss / refresh-from-source** (`dismissContractDrift`/`refreshContractFromSource`) | `project/ContractDriftModal.tsx` | **no** | no | The resolve-workflow half of drift. Gone. |
| ADRs: list | `project/AdrPanel.tsx` | yes | no | Signal `listProjectAdrs`. |
| **ADR detail** (`getProjectAdr`) | `project/AdrPanel.tsx` | **no** | no | Can't open an ADR. Gone. |
| Invariants + violations | `project/InvariantPanel.tsx` | yes | no | Signal `listProjectInvariants`/`listProjectInvariantViolations`. |
| Standards compliance report | `project/StandardsCompliancePanel.tsx` | partial | no | Read-only report present; see Org Standards CRUD below. |
| **PostMergeBar** (recent-merge banner) | `project/PostMergeBar.tsx` | **no** | no | `getPostMergeBar`/`dismissPostMergeBar`. Gone. |
| **Project score-trend chart** (`getProjectScores`) | `project/ProjectScoreTrend.tsx` | **no** | no | Score sparkline/trend. Gone. |
| Generated docs (EXPLANATION/SETUP get/generate/download) | `project/GeneratedDocsPanel.tsx` | **yes** | no | Signal `ProjectCodePage` (`generateProjectDoc`/`getProjectDoc`/`downloadProjectDoc`); IDE missing. |
| Project memory snapshot + compact | `project/ProjectMemoryPanel.tsx` | **yes** | no | Signal `ProjectSettingsPage` (`getProjectMemory`/`compactProjectMemory`); IDE missing. |
| Project chat (compact/clear/branch-override) | `project/ProjectChatPanel.tsx` | **yes** | partial | Signal full; IDE send+history only. |
| RAG documents (list/upload/delete) | `project/ProjectDocumentsPanel.tsx` | **yes** | no | Signal wires `uploadProjectDocument`/`listProjectDocuments`/`deleteProjectDocument`; IDE missing. |
| Activity feed | `project/ActivityTab.tsx` | **yes** | partial | Signal `getActivityFeed` w/ poll; IDE no filter/CSV/poll. |
| Repos: link/unlink/sync/webhook | `project/ProjectRepoManager.tsx`, `RepoCard.tsx`, `AddRepoModal.tsx` | **yes** | partial | Signal full; IDE can't link a repo (`linkProjectRepo`/`listRepos` unused). |
| **Repo relationship editor** (`updateRepoRelationships`) | `project/RepoRelationshipEditor.tsx` | **no** | no | Multi-repo relationship graph editing. Gone. |
| PR rollup + refresh | `project/ProjectPrRollup.tsx` | **yes** | partial | Signal `refreshProjectPrs`. |
| **Per-PR snapshot** (`getPullRequestSnapshot`/`getRunPrs`) | `project/ProjectPrRollup.tsx` | **no** | no | Per-PR CI/merge/headBranch deep view. Gone. |

### E. Run creation (compose)

| Feature | Earlier-chrome location | In Signal? | In IDE? | Notes |
| --- | --- | --- | --- | --- |
| Provider select + repo scoping + cost cap + p25/p75 estimate | `runs/NewRunModal.tsx`, `ProviderModelSelector`, `EstimateChip` | **yes** | partial | Signal `ComposePage` (provider incl. OpenRouter, `selectedRepoIds`, `userCostCap`, `estimateRun`). |
| **PROTOTYPE / PRODUCTION execution-mode toggle** | `runs/NewRunModal.tsx` | **no** (hardcoded PRODUCTION) | no | Signal hardcodes `executionMode:'PRODUCTION'`; the fast-first-run PROTOTYPE path isn't selectable. |
| **TDD mode toggle** (`tddMode`) | `runs/NewRunModal.tsx` | **no** | no | Gone from compose. |
| **Figma URL input** (`figmaUrl`) | `runs/NewRunModal.tsx` | **no** | no | Design-to-code input. Gone from compose. |
| Spec templates (list/create/delete) | `execution/TemplatePicker.tsx`, `TemplateGrid.tsx` | partial | no | Signal `SpecTemplatesPage` (`listSpecTemplates`); create/delete thin; IDE none. |
| "Build my spec" assistant | (v3 Composer) | yes | yes | Signal `SpecChat`. |

### F. V3 lanes: Library / Migrations / Insights / Onboarding

| Feature | Earlier-chrome location | In Signal? | In IDE? | Notes |
| --- | --- | --- | --- | --- |
| **Library** (browse readymade solutions by facet, instantiate) | `v3/pages/LibraryPage.tsx`, `LibraryDetailPage.tsx` | **partial (static stub)** | no | Signal `/library` renders a hardcoded array; `listLibrary`/`getLibraryFacets`/`instantiateLibrarySolution`/`resolveLibraryVariant` unused. |
| **Migrations** (recipe slot-fill + dry-run + apply) | `v3/pages/MigrationsPage.tsx`, `MigrationDetailPage.tsx` | **partial (static stub)** | no | Signal `/migrations` static; `listMigrations`/`migrationDryRun`/`migrationApply` unused. |
| Insights / analytics dashboard | `v3/pages/InsightsPage.tsx` | **yes** | no | Signal `InsightsPage` wires `getMetrics`. (`getModelComparison`/`getAnalyticsUsage` thin.) |
| **Onboarding sample picker** (`listOnboardingSamples`/`pickOnboardingSample`) | `pages/onboarding/RunsNewLanding.tsx`, `WelcomePage.tsx` | **no** | no | First-run sample-spec picker. Gone. |

### G. Multi-solution Execution lane (V3 `/api/executions`)

| Feature | Earlier-chrome location | In Signal? | In IDE? | Notes |
| --- | --- | --- | --- | --- |
| Whole arch-review → plan → multi-solution-decide → per-solution-runs pipeline | `pages/execution/ExecutionViewPage.tsx` + `components/execution/*` (`ExecutionStepper`, `ExecutionSetup`, `ExecutionAnalysis`, `ArchReviewGate`, `ExecutionPlanReview`, `ExecutionMonitor`, `CancellationPanel`, `SolutionSection`, `SolutionCard`, `FailureDecisionModal`, `DependencyGraph`, `StalenessWarning`, `ExecutionComplete`, `ExecutionReportPanel`) | **no** | no | Entire `executionApi` + `useExecutionStore` + `useExecutionSse` dark. `App.tsx` redirects `/projects/:id/executions/*` away. |
| `FailureDecisionModal` (per-failure decide: retry/skip/abort) | `execution/FailureDecisionModal.tsx` | no | no | Part of the lane. Gone. |
| `CancellationPanel` (live compensation/rollback view) | `execution/CancellationPanel.tsx` | partial | partial | Signal handles `EXEC_COMPENSATION_*` SSE on LivePage, but the dedicated cancellation panel is gone. |

### H. Org / members / sharing

| Feature | Earlier-chrome location | In Signal? | In IDE? | Notes |
| --- | --- | --- | --- | --- |
| Org members list + invite + remove + role | `pages/org/OrgSettingsPage.tsx`, `OrgProjectsPage.tsx` | **yes** | no | Signal wires members/invites; IDE org surface absent (uses Signal fallback). |
| Project members + cross-org invites + share-links | `project/` member panels | **yes** | partial | Signal full; IDE Members view+remove only. |
| **`createOrg`** (create a new org) | `pages/org/*` | **no** | no | Gone. |
| **`adjustOrgSeats`** (TEAM seat management) | `pages/admin/OrgAdminDashboard.tsx` | **no** | no | Gone. |
| **`orgSuspendMember`** | `pages/org/*` | **no** | no | Gone. |
| **SSO config** (get/update/clear) | `settings/SsoConfigCard.tsx` | **yes** | no | Signal wires `getSsoConfig`/`updateSsoConfig`/`clearSsoConfig` (in LegalPages); IDE missing. |
| **Org standards CRUD** (`createOrgStandard`/`updateOrgStandard`/`deleteOrgStandard`) | `settings/OrgStandardsManager.tsx` | **no** | no | Read-only report survives; authoring gone. |
| Notification preferences | `settings/NotificationPreferences.tsx` | **yes** | no | Signal `AccountPage`; IDE missing. |

### I. Billing / accounting / admin

| Feature | Earlier-chrome location | In Signal? | In IDE? | Notes |
| --- | --- | --- | --- | --- |
| Plans + subscribe + top-up | `billing/PlanCard.tsx`, `pages/PricingPage.tsx` | **yes** | partial (Signal fallback) | Signal `PlansPage`/`BillingPage`. |
| **Billing profile** (`getBillingProfile`/`updateBillingProfile`) | `billing/BillingProfileForm.tsx` | **no** | no | GST/business billing profile. Gone. |
| **Accounting: GST summary, trial balance, GSTR-1 export** | `pages/AccountingPage.tsx` | **no** | no | `getGstSummary`/`getTrialBalance`/`getGstr1ExportUrl`. Gone. |
| **Invoices: list, download PDF, resend** | `pages/InvoicesPage.tsx` | **no** | no | `getAllInvoices`/`downloadInvoicePdf`/`resendInvoice`. Gone. |
| **Audit log + chain verify** | `pages/AuditPage.tsx` | partial | partial | `getAuditLogs` present (1 file each); `verifyAuditChain` gone. |
| Admin: activation funnel + tiers + feature-flag list | `pages/admin/*` | partial | partial | Both thin. |
| **Admin: user mgmt / org mgmt / grant-runs / topup CRUD / payment-audit CSV / webhook events / model-pricing / usage-report / halt-all / escalated invites / member+billing analytics** | `pages/admin/*` (`AdminPage`, `OrgAdminDashboard`, `TeamMemberDashboard`, `PricingManager`, `TierLifecyclePage`, `FeatureFlagsPage`, `GrantRunsModal`, `AdminInviteModal`) | **no** | no | ~30+ admin functions dark in both. |

### J. Integrations

| Feature | Earlier-chrome location | In Signal? | In IDE? | Notes |
| --- | --- | --- | --- | --- |
| Connect GitHub / Slack + status | `pages/IntegrationsPage.tsx`, `integrations/IntegrationCard.tsx` | **yes** | partial | `getIntegrationStatus`/`getSlackStatus`/`getGitHubAppStatus`. |
| **Slack channel mapping + test message** (`listSlackChannels`/`sendSlackTestMessage`) | `pages/IntegrationsPage.tsx` | **no** | no | Gone. |
| **Jira/Confluence/Bitbucket validate + settings** (`validateOrgJira`/`validateOrgConfluence`/`validateBitbucket`/`updateOrgJiraSettings`/`updateOrgConfluenceSettings`/`updateGithubSettings`/`updateOrgKeySourceSettings`) | `pages/IntegrationsPage.tsx`, `runs/InlineCredentialDrawers.tsx` | **no** | no | Gone. |
| **Per-integration run history** (`getIntegrationRuns`) | `pages/IntegrationsPage.tsx` | **no** | no | Gone. |

### K. Test generation

| Feature | Earlier-chrome location | In Signal? | In IDE? | Notes |
| --- | --- | --- | --- | --- |
| Test report | `runs/TestReportPanel.tsx`, `TestProgressTracker.tsx` | **yes** | partial | Signal `getValidationReport`/test tab; `runTests` wired. |
| Apply test fixes (`applyTestFixes`) | (run detail) | **yes** | no | Signal `ResultPage`; IDE missing. |
| **Test plan view** (`getTestPlan`) | (testgen surface) | **no** | no | Gone. |
| **Mark test invalid** (`markTestInvalid`) | (test report) | **no** | no | Gone. |

---

## Renamed / superseded (NOT losses — listed to avoid double-counting)

These earlier-chrome functions appear "missing" by name but the capability is present under a newer name:

| Earlier-chrome fn | Current equivalent | Where |
| --- | --- | --- |
| `pushToGitHub` | `pushToScm` (multi-repo) | `ResultPage`, `IdeRunResult` |
| `syncRun` / `syncRunToOriginal` | `syncRepo` (+ `/sync` endpoints) | `ProjectReposPage` |
| `getProject` / `getProjectSummary` | `getProjectDashboard` | project pages |
| `getMyPermissions` | `getMyPermissions` via `useTierPermissions` hook | shared hook (present) |
| `getPublicFeatureFlags` / `adminListFeatureFlags` | `adminListFeatureFlags` | Signal `AdminPage` (present) |
| `fetchBillingStatus` | `getAnalyticsBilling` / billing status in `BillingPage` | account billing |
| `getRequiredPermissions` | folded into one-click deploy permission check | n/a (wizard removed) |

---

## Appendix — full list of the 111 api.ts functions called by earlier chrome but by neither Signal nor IDE

> Verified by diffing earlier-chrome source (`d0e643ec`) against the live `signal/` + `ide/` tree (multiline-import-aware extraction + per-function strict call-site grep). Of these, only `getV3Entitlements`, `getV3ExecSnapshot`, `sendV3Referral` were also removed from `lib/api.ts`; **every other function is still a live export** — i.e. a UI-only loss.

```
acceptInvite                  getAllInvoices                migrationDryRun
addProjectMember              getAnalyticsMembers           orgSuspendMember
adjustOrgSeats                getCodeReview                 pickOnboardingSample
adminApproveInvite            getDeploymentDetail           probeApiDocs
adminCreateTopupPackage       getGstSummary                 pushToGitHub*
adminDeleteTopupPackage       getGstr1ExportUrl             rebuildPreview
adminExportPaymentAuditCsv    getIntegrationRuns            resendInvoice
adminGetTierConfig            getLatestCodeReviewForRepo    resolveLibraryVariant
adminGetUser                  getLiveCost                   sendSlackTestMessage
adminGrantOrgRuns             getOpenApiSpec                stopPreview
adminGrantUserRuns            getPrReview                   submitCodeReview
adminInviteUser               getPreviewLogs                submitPrReview
adminListEscalated            getPreviewStatus              syncRun*
adminListPaymentAudit         getProjectAdr                 syncRunToOriginal*
adminListUsers                getProjectChatInfo            unarchiveProject
adminListWebhookEvents        getPullRequestSnapshot        updateBillingProfile
adminRejectInvite             getRequiredPermissions        updateGithubSettings
adminTriggerUsageReport       getRunPrs                     updateModelPricing
adminUpdateFeatureFlag        getTestPlan                   updateOrgConfluenceSettings
adminUpdateOrgApiKeys         getTrialBalance               updateOrgJiraSettings
adminUpdateOrgRunLimit        haltAllActiveRuns             updateOrgKeySourceSettings
adminUpdateOrgStatus          instantiateLibrarySolution    updateOrgStandard
adminUpdateOrgTier            iterateRun                    updateRepoRelationships
adminUpdateTierConfig         listCodeReviewsForProject     updateRunProject
adminUpdateTopupPackage       listGitHubRepos               updateTierLifecycle
adminUpdateUserRole           listLibrary                   validateBitbucket
adminUpdateUserStatus         listMigrations                validateGitHub
adminUpdateUserTier           listOnboardingSamples         validateOrgConfluence
analyseProject                listPrReviewsForPr            validateOrgJira
analyzeForDeploy              listSlackChannels             verifyAuditChain
analyzePreview                markTestInvalid               (+ also dropped from api.ts:
annotateArchitecture          migrationApply                 getV3Entitlements,
applyToRun                    createOrg                       getV3ExecSnapshot,
authChangePassword            createOrgStandard               sendV3Referral)
codeReviewReportDownloadUrl   createRepo
confirmDeploy                 deleteOrgStandard             Also confirmed gone (caught
dismissContractDrift          dismissPostMergeBar            via a different legacy call
engineDestroy                 engineRollback                 pattern): pauseRun, resumeRun,
fetchBillingStatus            getActivationFunnel*           getProjectScores.
```
`*` = capability survives under a renamed function (see "Renamed / superseded").

---

## Recommended sequencing (earlier-chrome-loss lens)

1. **PR Review + Code Review submit/scan/autofix** — these are named product surfaces (2 of 4 autofix paths) and the most defensible "we used to have this." Highest user value.
2. **Live preview embed + lifecycle** — the single biggest demo/credibility regression; one-click `startPreview` with no visible app is a poor experience vs the old iframe + console.
3. **Compose options** (PROTOTYPE/PRODUCTION, TDD, Figma URL) — cheap to restore (the API already accepts the fields), restores the fast-first-run path for evaluators.
4. **Run power-ops** (pause/resume, iterate-as-new-run, apply-to-run) — completes the Wave A1 run-management restore.
5. **Decide, don't auto-build, the bigger lanes** — the multi-solution Execution lane, Library/Migrations going live, full Accounting/GST, and the full admin console are product decisions (is the V3 execution lane being kept? should Library be live or stay marketing-static?). Triage with the founder, consistent with `feature-gap-comparison.md`'s recommendation.
6. **IDE-specific catch-up** — much of the project workbench (tech-debt, docs, memory, RAG docs, repo-linking) exists in Signal but not IDE; this overlaps with Bucket B in `feature-gap-comparison.md`.
