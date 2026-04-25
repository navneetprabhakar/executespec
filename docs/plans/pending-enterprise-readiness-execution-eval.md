# Enterprise Readiness — Evaluation + Slice-1 Sequencing

> Status: draft for review (2026-04-25). Evaluates the two source docs against
> the current dev state of ExecuteSpec (v2.6.1 deployed; v2.6.2-track
> hierarchical-planner pending merge) and proposes a v1 ship plan that
> preserves the existing UX system. Author judgements are called out
> explicitly — they are not echoes of the source docs.
>
> **Source docs** (live outside the repo):
> - `~/Downloads/project-enterprise-readiness-plan.md` — design lock, 5 feature areas
> - `~/Downloads/project-enterprise-readiness-execution.md` — task slicing, ~46 numbered tasks across 4 phases
>
> **Dependencies:** none for this doc. Phase 1 of the actual work depends
> on `feature/hierarchical-planner` merging to `dev` (V130 lands first,
> reserves the migration number this plan claims at V131+).
>
> **Status of work:** not started. This is an evaluation + sequencing
> proposal. No code shipped under this branch beyond this doc.

---

## Goal

Decide what of the source plan ships in v1, in what order, and on top of
which existing primitives — without re-skinning the app. Output a
shippable Phase 1 sequence (foundational `.executespec/` baseline +
activity-feed taxonomy) that a single engineer can execute against
without rewriting the dashboard.

## Non-goals

- Not re-litigating the source plan's binding constraints (no caps for
  ENTERPRISE/MAX, no preview in Project, JIRA optional, Initiative →
  Launch hierarchy, separate-Project for confidential isolation, no
  cross-project bridges, `.executespec/` is the only persistent
  customer-visible surface). Those are inputs.
- Not designing the chat-actions surface — explicitly deferred per
  source plan §7.
- Not building monorepo scope-aware planning — only the detection
  chip ships in v1.
- Not building any new design tokens, fonts, or component primitives.
  Every UI surface in this plan must map to an existing primitive +
  token. See §3.

---

## 1. UX-preservation contract (binding for this work)

User constraint, recorded verbatim and treated as a hard rule:

> Implementation constraints, UX (CSS) doesn't change, fonts color codes
> and UX implementation philosophy is maintained.

Practical interpretation:

- **Tokens.** No additions to `frontend/src/tokens.css`. The 4-band
  semantic palette (`--status-success / warning / partial / fail`),
  Signal Blue brand accent (`#5B8DEF`), gray scale, and money colour
  carry every new chip and badge. See [`docs/design/ui-tokens.md`](../design/ui-tokens.md).
- **Fonts.** Existing display + mono families only. No new weights.
- **Primitives.** All modals reuse [`ResponsiveModal.tsx`](../../frontend/src/components/shared/ResponsiveModal.tsx)
  (focus-trap + Escape + `disableDismiss` + `hideCloseButton` already
  hardened). All buttons reuse [`Button.tsx`](../../frontend/src/components/shared/Button.tsx)
  with existing `variant` × `size` matrix. All tooltips reuse
  [`Tooltip.tsx`](../../frontend/src/components/shared/Tooltip.tsx)
  (CSS-only, 250ms delay). Form controls reuse `Checkbox.tsx` and
  `Radio.tsx`.
- **IA philosophy.** The v2.6.0 5-group dashboard layout (Overview /
  Work / Knowledge / Settings / Chat) becomes a 6-group layout with
  Activity inserted between Knowledge and Settings — that is the *only*
  IA mutation. Tab IDs stay string-based so existing deep links keep
  working. Header breadcrumb is already dynamic per route.
- **Visual patterns.** Per-repo cards reuse the
  [`WorkspaceHealthPanel.tsx`](../../frontend/src/components/project/WorkspaceHealthPanel.tsx)
  shape. Per-row standards/issues panels reuse
  [`TechDebtPanel.tsx`](../../frontend/src/components/project/TechDebtPanel.tsx).
  Banner stacking reuses [`PostMergeBar.tsx`](../../frontend/src/components/project/PostMergeBar.tsx)
  (Redis list, max 3 + "+N more" chip). Diff visuals reuse the LCS
  renderer in [`ArchitectureViewer.tsx`](../../frontend/src/components/codeintel/ArchitectureViewer.tsx).
- **Email + PDF.** No template changes; the 17-template palette swap
  in v2.6.0 covers brand consistency.

If a task in this plan can't be expressed within these constraints, it
gets escalated rather than implemented with new tokens.

---

## 2. Current state vs plan (snapshot)

| Plan area | What's in the code today | % done | Key reusable primitives |
|---|---|---|---|
| 1. `.executespec/` folder | Per-project workspace at `workspace/projects/<projectId>/` ([`ProjectWorkspaceService`](../../src/main/java/com/navneet/executespec/project/workspace/ProjectWorkspaceService.java)) — server-side shadow workspace, NOT a customer-repo folder. Codemap at `codemaps/<repo>.md` + DB ([`ProjectArchitectureService`](../../src/main/java/com/navneet/executespec/codeintel/ProjectArchitectureService.java)). No `.executespec/` namespace anywhere. | ~30% | [`GitHubRepoService`](../../src/main/java/com/navneet/executespec/integration/github/GitHubRepoService.java) (branch + PR + closePR + compareCommits + installWebhook), `ProjectRepoService.linkRepo` hook point |
| 2. Cross-repo contract registry | Zero. `ApiTestSuiteAgent` writes per-run `api-docs/openapi.yaml` only. No producer/consumer/version/drift/classifier tables. | ~5% | [`StackDetector`](../../src/main/java/com/navneet/executespec/project/service/StackDetector.java), `ProcessExecutor` command-allowlist |
| 3. Project activity feed | `activity_audit` (V22) exists for user-management compliance only. Frontend `ActivityFeed` component renders runs, not events. No 3-tier taxonomy, no `activity_feed` table, no project tab. | ~25% | [`InAppNotificationService`](../../src/main/java/com/navneet/executespec/notification/InAppNotificationService.java), [`RunCompletionNotifier`](../../src/main/java/com/navneet/executespec/notification/RunCompletionNotifier.java), `@Scheduled + @SchedulerLock` retention pattern |
| 4. Export bundle | Zero. No controller, no bundling, no rate limit. All inputs (workspace, memory, runs, audit) are queryable in isolation. | 0% | Existing GCS client (deployment), `RateLimitFilter` group enum |
| 5. Standards / ADR / invariants | Zero standards/invariants surface. ADR concept absent (closest analog is `ProjectMemorySnapshot`). RBAC + tier scaffolding (`TierConfig`, `ProjectMember.projectRole`) reusable for org/project gating. | ~10% | `TierConfig`, `ProjectMember`, `ProjectMemoryProvider` (template for org→project inheritance pattern) |

---

## 3. UX-constraint mapping (every new surface → existing primitive)

This is the binding contract for any frontend work in this plan. If a
new surface isn't on this list, it doesn't ship.

| New surface | Reuses | Token / colour |
|---|---|---|
| Bootstrap-PR-failed banner | `PostMergeBar`-style banner shape | `--status-partial` background |
| Coverage panel (per-repo cards) | `WorkspaceHealthPanel` layout, flat per-repo cards (no accordions) | 4-band status chips |
| Drift modal | `ResponsiveModal` + LCS diff from `ArchitectureViewer` | `--status-warning` header |
| Consumer-declare modal | `ResponsiveModal` + existing combobox | brand Signal Blue primary `Button` |
| Breaking-change confirm modal | New typed-input pattern *inside* existing plan-approval modal | amber `--status-partial` warning block; primary `Button` disabled until "BREAKING" typed |
| Override-rationale modal | `ResponsiveModal` + textarea, ≥30 chars enforced via `Tooltip` on disabled submit | neutral header; severity chip in body |
| Standards report sub-tab | New sub-tab inside existing **Knowledge** group (no IA mutation here) | severity chips: BLOCKING=`--status-fail`, WARNING=`--status-partial`, ADVISORY=brand Signal Blue |
| Standards compliance section in plan-gate | Inline section in existing `PlanCard` | "Plan satisfies all N standards" or amber list |
| Activity tab | New top-level tab = 6th group between Knowledge and Settings (IA's *only* mutation) | tab nav supports any count; chips = 4-band tokens |
| Activity event-detail modal | `ResponsiveModal`; JSON renders in mono family | neutral |
| Notification preferences UI | Existing Settings-page section + `Checkbox` | default-state badge |
| Export request UI | Project Settings new section: `Button` "Request Export" + status chip + history table | brand Signal Blue primary; download CTA via in-app notification |

The 4-band semantic palette + Signal Blue brand accent cover every
surface above. **No new colours, fonts, or icon families are required
to ship this plan.**

---

## 4. Detailed task list

Each task gets an ID I'll use as the PR title prefix (e.g.
`E1.4: ExecutespecFolderWriter chokepoint`). Status legend:

- **NEW** — no equivalent exists
- **PARTIAL** — overlap with existing code; needs reshape
- **EXISTS** — present, only wiring needed

Effort is my own estimate (single engineer, calendar days, includes
test + doc-refresh per CLAUDE.md house rule).

### Phase E1 — `.executespec/` baseline + activity taxonomy (foundational, must merge first)

| ID | Task | Status | Reuses / risk | Effort |
|---|---|---|---|---|
| E1.1 | V131 — `project_repo` adds `executespec_folder_status / sha / bootstrap_pr_url / last_synced_at` + index | NEW | Coordinate V131 with `feature/hierarchical-planner`'s V130. Pure additive. | 0.5 d |
| E1.2 | Codemap relocates `codemaps/<repo>.md` → `.executespec/arch/codemap.md` (PR-gated) | PARTIAL | Touches [`ProjectArchitectureService`](../../src/main/java/com/navneet/executespec/codeintel/ProjectArchitectureService.java), [`StalenessService`](../../src/main/java/com/navneet/executespec/codeintel/StalenessService.java), [`PostMergeCascadeService`](../../src/main/java/com/navneet/executespec/project/cascade/PostMergeCascadeService.java), [`ProjectWorkspaceWarmer`](../../src/main/java/com/navneet/executespec/project/workspace/ProjectWorkspaceWarmer.java). **Behavioural regression**: cascade flow goes from "regenerate in seconds" to "regenerate → PR → wait for merge". Dashboard stays STALE until customer merges. See §6 con #2. | 5–8 d |
| E1.3 | `POST /api/admin/projects/{id}/migrate-codemaps` (SUPER_ADMIN, idempotent) | NEW | Reuse [`SuperAdminController`](../../src/main/java/com/navneet/executespec/auth/controller/SuperAdminController.java) RBAC. | 1 d |
| E1.4 | `ExecutespecFolderWriter` — single chokepoint for all `.executespec/` writes (PR-only) | NEW | Wraps existing `GitHubRepoService` primitives. **Must define conflict-resolution policy** (see §6 con #6). | 4 d |
| E1.5 | Bootstrap PR on `ProjectRepoService.linkRepo` + **pre-flight PAT scope check** (my addition vs source plan) | PARTIAL | Same hook as `installWebhook`. Pre-flight check converts "first PR failed" into "missing scopes" tooltip at link time. | 3 d |
| E1.6 | `FolderReadmeGenerator` — `.executespec/README.md` from template | NEW | Trivial. | 1 d |
| E1.7 | Project-namespaced configs `.executespec/config/<project-slug>/` for multi-project repos + `config_storage: database` opt-out | NEW | Edge case: existing flat config gets migrated on second link via PR. | 3 d |
| E1.8 | `ActivityEventType` enum + `@ActivityMeta(tier, surfaces, schemaVersion)` annotation | NEW | Foundational — every downstream feature emits these. Ship the full enum from source plan §3.2/§3.3 in one go. | 2 d |
| E1.9 | `ActivityEventEmitter` — async chokepoint + dual-write to `activity_audit` for AUDIT-surface events | NEW | Reuse `InAppNotificationService` for IN_APP delivery + existing async executor pattern from `V2ApplyService`. Failures logged + retried; never block caller. | 2 d |
| E1.10 | V132 — `activity_feed` partitioned table (monthly) + `ActivityFeedPartitionJob` daily | NEW | New table; do **not** touch `activity_audit`. Reuse `@SchedulerLock` pattern from `StackRefreshJob`. | 2 d |

**Phase E1 total: ~24 days. Hard sequence inside the phase.**

### Phase E2 — Contract registry (depends on E1)

| ID | Task | Status | Reuses / risk | Effort |
|---|---|---|---|---|
| E2.1 ✓ | V135 — `contract_registry / contract_version / contract_consumer / contract_drift_event` (V133 was claimed by E1.10) | NEW | Standalone tables; FK on `project_repo` only. **Shipped 2026-04-25.** `mvn test` 368 of 368 (6 new in `ContractRegistryEntityTest`); migrations applied to live Postgres @ V135. | 1 d |
| E2.2 ✓ | 5 file-based detectors + `ContractDetectionService` orchestration | NEW | 5 detectors (OpenAPI / Proto / GraphQL / JSON Schema / AsyncAPI) + shared `DetectorUtils` (sha256, glob match, version pull). Service orchestrates: list files → run detectors → upsert registry+version → emit `CONTRACT_DETECTED` / `CONTRACT_VERSION_CHANGED`. Multi-file `$ref` bundling deferred (logged in §13). **Shipped 2026-04-25.** `mvn test` 386 of 386 (18 new across 2 test files). | 6–8 d |
| E2.3 ✓ | Coverage backend — `GET /api/projects/{id}/repos/{repoId}/contract-coverage` (cached) + `POST .../rescan` (on-demand) | NEW | Cached read groups existing `contract_registry` rows by type per detector; rescan path runs `ContractDetectionService.runForRepo` with the GH-backed `FileLister` + `ContentLoader`. Hardcoded knownGaps[] surfaces categories ExecuteSpec doesn't auto-detect (gRPC-in-code, runtime OpenAPI, external registries, monorepo scoping) so customers never silently miss a category. **Shipped 2026-04-25.** `mvn test` 391 of 391 (5 new in `ContractCoverageControllerTest`). | 2 d |
| E2.4 ✓ | `ContractCoveragePanel.tsx` + wired into Project Overview tab | NEW UI | UX-preserved: reuses `WorkspaceHealthPanel` card shape + 4-band semantic chips (success/warning/partial/fail) + monospace repo name + Refresh/Re-scan button pair. No accordions, no new tokens, no new fonts. One panel rendered per linked repo on the Overview tab. **Shipped 2026-04-25.** Frontend build green (4043 modules); Vite dev server boots without import errors; preview-verified the dashboard still renders cleanly with the new component on the bundle path. | 3 d |
| E2.5 ✓ | `ContractRepoClassifier` (heuristic-only this slice) | NEW | Three-signal heuristic: name match (`contracts/schemas/proto/idl/specs` at word boundary in repo basename) + no application-code markers (`pom.xml / build.gradle / Cargo.toml / go.mod / pyproject.toml / Dockerfile`) + contract-file density ≥0.7. Verdict has `Confidence.{CONFIDENT, AMBIGUOUS}` so the UI can prompt for confirmation when only one signal fires. LLM verifier for ambiguous cases + producer-matching across linked repos both deferred to follow-ups. **Shipped 2026-04-25.** `mvn test` 401 of 401 (10 new). | 3 d |
| ~~E2.6~~ | ~~Confluent schema registry integration~~ | NEW | **CUT from v1 (see §7).** Defer to v1.1. | 0 d |
| E2.7 | Breaking-change classifier — `oasdiff / buf breaking / graphql-inspector` wrappers + LLM fallback | NEW | Adds 3 CLI binaries to the runtime container. Reuse `ProcessExecutor` allowlist. | 6 d |
| E2.8 | Drift detection backend (3 endpoints + implicit pre-plan trigger) | NEW | Implicit trigger lives in `V2RunOrchestrator.runPipeline` — guard against latency. | 3 d |
| E2.9 | Drift detection frontend | NEW UI | UX: `ResponsiveModal` + LCS diff visual + per-repo chip on `WorkspaceHealthPanel`. | 3 d |
| E2.10 | Consumer declaration backend + UI | NEW | Modal = `ResponsiveModal` + repo combobox. | 3 d |
| E2.11 | `{{CONTRACTS}} / {{CONTRACTS_INDEX}}` planner injection + `getContract` tool | NEW | Slot fits next to `{{REFERENCE_DOCS}} / {{PROJECT_BRIEF}} / {{PROJECT_MEMORY}}` in [`PlannerAgentV2`](../../src/main/java/com/navneet/executespec/v2/agent/PlannerAgentV2.java). **Token-budget risk** with hierarchical-planner — see §6 con #9. | 3 d |
| E2.12 | Breaking-change UX at plan-approval gate (typed "BREAKING" confirmation) | NEW | UX: typed-input inside existing plan-approval modal; amber `--status-partial`. Pattern reusable for any future high-friction confirmation. | 3 d |
| E2.13 | Monorepo detection chip (graceful degradation; no scope-aware planning) | PARTIAL | `StackDetector` already returns build signals. Add `is_monorepo` column + chip on RepoCard. | 2 d |

**Phase E2 total (with E2.6 cut): ~38 days.**

### Phase E3 — Engineering standards (depends on E1; can overlap with E2)

| ID | Task | Status | Reuses / risk | Effort |
|---|---|---|---|---|
| E3.1 | V134 — `org_config_repo / org_standard / project_standard_override / standards_violation` | NEW | Standalone tables. | 1 d |
| E3.2 | YAML standards model + parser + validator | NEW | SnakeYAML on classpath. | 2 d |
| E3.3 | `EffectiveStandardsResolver` (org → project merge, severity-downgrade-only, `org_level_locked`) | NEW | Cacheable (Caffeine). | 2 d |
| E3.4 | Lint adapters — **ship 2 in v1: ESLint + Checkstyle** (cut from 5 in source plan) | NEW (cut) | Each adapter shells out via `ProcessExecutor`. **Container bloat:** 2 toolchains pre-installed. Detekt/ruff/golangci on demand. | 3 d (cut) |
| E3.5 | File-pattern enforcer (glob forbidden/required) | NEW | Trivial. | 1 d |
| E3.6 | `LlmReviewEnforcer` — appends `review_prompt` to `ReviewerAgentV3` system prompt per applicable standard | NEW | Drops into [`ReviewerAgentV3`](../../src/main/java/com/navneet/executespec/v2/reviewer/ReviewerAgentV3.java). **Cost watch:** N standards × LIGHTWEIGHT call per run. | 3 d |
| E3.7 | Per-language AST adapters — **ship Java + TypeScript only in v1** (cut from 5) | NEW (cut) | JavaParser in-process (cheap); TS Compiler API via Node bridge. Symbol-table cache keyed `(repo_id, branch, file_sha)`. Python/Kotlin/Go on demand. | 8 d (cut from 15–20 d) |
| E3.8 | AST rule kinds — **ship 3 in v1: forbidden-import, naming-pattern, dependency-direction** × 2 languages = 6 implementations (cut from 10 × 5 = 50) | NEW (cut) | Covers ~80% of customer demand. | 5 d (cut from 12+) |
| ~~E3.9~~ | ~~Custom-script (`kind: custom`) sandbox runner~~ | NEW | **CUT from v1 (see §7).** JS-in-JVM sandboxing is a security minefield. Defer. | 0 d |
| E3.10 | Worker + reviewer integration of all 4 enforcement types + `{{ENGINEERING_STANDARDS}}` prompt block | NEW | Wires to `SequentialExecutorV2` + `ReviewerAgentV3`. Worker self-check retry × 2 within existing iteration caps. | 4 d |
| E3.11 | Override flows (3 types) + pattern-detection job + recommendation notifications | NEW | UX: `ResponsiveModal` + textarea ≥30 chars. `@Scheduled + @SchedulerLock`. Notification via `InAppNotificationService`. | 3 d |
| E3.12 | V135 — `adr` table + frontmatter validator + ADR template + PR-merge validation hook | NEW | Template ships in bootstrap PR (E1.5). PR validation = GitHub Actions snippet shipped in `.executespec/`. | 4 d |
| E3.13 | `{{PROJECT_ADRS}}` planner injection (with index+tool-call fallback >4k tokens) | NEW | Same pattern as E2.11. | 2 d |
| E3.14 | V136 — `arch_invariant / arch_invariant_violation` + 3 dependency-check rule kinds + `{{ARCHITECTURE_INVARIANTS}}` injection | NEW | Reuses AST cache from E3.7. | 5 d |
| ~~E3.15~~ | ~~Normative→standards auto-migration tool~~ | NEW | **CUT from v1 (see §7).** Ship as opt-in admin helper post-v1. | 0 d |
| E3.16 | Standards compliance UI surfaces (gate, reviewer output, PR description, Knowledge sub-tab report) | NEW UI | UX: standards report = sub-tab in **Knowledge** group (no IA change); per-standard cards = `TechDebtPanel` row shape; severity chips = 4-band tokens. | 4 d |

**Phase E3 total (with cuts): ~47 days. Source plan at full scope: ~75–95 days.**

### Phase E4 — Activity feed UI + export (depends on E1; can overlap with E2/E3)

| ID | Task | Status | Reuses / risk | Effort |
|---|---|---|---|---|
| E4.1 | `ActivityFeedController` — REST + filters + saved-filter persistence | NEW | Cursor pagination from `RunController` pattern. | 3 d |
| E4.2 | `ActivityTab.tsx` — virtualised list + filter bar + event-detail modal | NEW UI | UX: react-window (already in deps); filter chips like `RunsListPage`; modal = `ResponsiveModal`. | 5 d |
| E4.3 | `ProjectDashboardPage.tsx` IA: 5 → 6 groups | PARTIAL | **Only IA mutation in this whole plan.** Tab IDs stay string-based; deep links unaffected. | 1 d |
| E4.4 | Verify audit/activity separation | EXISTS-ish | `activity_audit` stays as-is; `activity_feed` is separate. Audit endpoint already gated by `canViewAudit`. | 0.5 d |
| E4.5 | `user_notification_pref` + Settings UI per-event-type toggles | PARTIAL | UX: existing Settings section + `Checkbox`. | 4 d |
| E4.6 | `ActivityFeedRetentionJob` — daily partition drop per tier | NEW | `@Scheduled + @SchedulerLock`. ENTERPRISE = no drop. | 1 d |
| E4.7 | Export bundle generator + `ProjectExportJob` + GCS upload + signed URL | NEW | GCS client present (deployment uses it). Rate limit (1/h, 5/d) via `RateLimitFilter` group. | 5 d |

**Phase E4 total: ~20 days.**

### Cross-cutting

- **CC.1** Doc-refresh per CLAUDE.md house rule — same commit as code,
  no separate "fix docs" follow-up. Updates flow into root `CLAUDE.md`,
  per-package `CLAUDE.md`, [`CODEMAP.md`](../CODEMAP.md), this file's
  status. ~2–3 d distributed across phases.
- **CC.2** Backlog tickets for the 8 deferred items in source plan §7
  (chat actions, monorepo scope-aware planning, integration test
  generation, webhook export, scheduled exports, org-config repo
  storage mode, cross-project consumer visibility, continuous arch
  conformance UI). 0.5 d.

---

## 5. Pros (author judgement, not echoes of the source doc)

1. **Git-as-source-of-truth for `.executespec/` is the right call for
   the picky enterprise persona.** Most config-as-code initiatives die
   because they're optional; making it the only persistent surface
   forces consistency and converts every change into a reviewable
   artifact. Structurally correct.
2. **Deterministic breaking-change classifiers > LLM.** `oasdiff` /
   `buf breaking` / `graphql-inspector` are years-hardened, stable, and
   token-free. LLM-as-fallback is the right escape hatch — most plans
   get this backwards and pay LLM cost for what tools already solve.
3. **Threshold-based contract injection (≤15 inline / >15 tool-call)
   is genuinely thoughtful prompt engineering.** Most teams would inject
   everything and burn tokens. The `getContract` tool reuses our
   existing tool-calling infra cleanly.
4. **Activity feed separated from audit is architecturally clean.**
   Two tables, two retention policies, two RBAC predicates, one mental
   model per surface. The 3-tier event taxonomy
   (high-signal / admin-toggle / never) is the discipline that prevents
   feed-noise.
5. **Severity-downgrade-only + `org_level_locked` standards** maps
   directly to how compliance functions in real enterprises — central
   security/platform team owns "blocking", project teams can soften
   but not loosen the central rule. Procurement-friendly.
6. **Bootstrap PR doubles as a permission probe** (clever) — surfaces
   PAT scope gaps at link time instead of failing silently a week
   later. With the pre-flight check added in E1.5, this becomes a
   strong onboarding moment instead of a hostile one.

## 6. Cons (author judgement — risks the source doc papers over)

1. **Scope is much larger than implied.** ~46 numbered tasks, each with
   non-trivial integration surface. Realistic effort at full scope is
   6–9 months for two engineers. Phase 3 alone (AST × 5 langs × 10 rule
   kinds + custom-script sandbox + 5 lint adapters + symbol cache) is a
   quarter on its own. **Severity: HIGH.**
2. **Codemap migration to PR-gated is a behavioural regression dressed
   as additive.** Today: post-merge cascade regenerates codemap in
   seconds, dashboard refreshes. After E1.2: regenerate → PR → wait for
   human merge → minutes-to-days of stale state. Source plan §1.7
   acknowledges this but doesn't grade UX impact. Customers will notice
   immediately. **Severity: HIGH.** Mitigation in §7.
3. **PR-for-every-change traps customers in bot-PR fatigue.** Every
   `standards.yaml` edit, consumer declaration, drift refresh, codemap
   regen, ADR creation = a PR. A project linked to 5 repos with 50
   standards produces dozens of bot-PRs daily. Source plan defers
   "chat-action batching" but underestimates how fast this bites. Within
   4 weeks of GA customers will demand a batch mode or opt-in
   direct-write for trusted ops. **Severity: HIGH.** Mitigation in §7.
4. **AST framework cost-vs-payoff is upside-down for the median
   customer.** 10 declarative rule kinds × 5 languages = 50
   implementations. Most enterprises run 3–5 standards and would be
   served by lint + LLM-review alone. Build the smaller surface, prove
   demand, then expand. **Severity: HIGH.** Mitigation in §7 (E3.7/3.8
   cuts).
5. **Enum-based event taxonomy locks evolution to a code deploy.**
   `ActivityEventType` as a Java enum means adding a customer-requested
   event type ships only via release. Within 6 months customers will
   want their own event types (custom run states, integration hooks).
   Need a string-typed open-set escape hatch from day one. **Severity:
   MEDIUM.**
6. **Conflict resolution for `.executespec/` PRs is undefined.** When a
   customer's manual edit to `standards.yaml` collides with an
   ExecuteSpec-opened refresh PR, who wins? Source plan says "manual
   edits preserved across detection runs" but doesn't define merge
   semantics. **#1 production bug after GA.** Must define in E1.4.
   **Severity: HIGH.**
7. **Confluent integration (E2.6) is a 3-week project sized as one
   task.** Auth modes, namespace filters, polling backpressure, rate
   limits, schema-evolution semantics. Defer to v1.1. **Severity:
   MEDIUM.** Mitigated by §7 cut.
8. **Permission-probe-via-PR-failure is UX-hostile for first-time
   users** without a pre-flight check. "Welcome! Your first PR
   couldn't be opened" is a bad onboarding moment. E1.5 in this plan
   adds a pre-flight scope check the source plan omits. **Severity:
   MEDIUM** (easy fix, but missing in source).
9. **Token-budget collision risk with hierarchical-planner.** Slice 5b
   centralized planning around `OutlinePlannerAgent` with a 32k cap.
   Adding `{{CONTRACTS}}` (≤15 full bodies) + `{{PROJECT_ADRS}}` +
   `{{ENGINEERING_STANDARDS}}` + `{{ARCHITECTURE_INVARIANTS}}` could
   push the system prompt past 20k on its own, before the spec arrives.
   Need a `PromptAssembler` token-budget governor that auto-degrades
   full→index when total > N tokens. **Severity: MEDIUM.** New task
   added in §7.
10. **Worker container Dockerfile bloat: JDK + Node + Kotlin + Python +
    Go + 3 breaking-change CLIs + 5 linters = multi-GB image.**
    Cold-start latency for short runs visibly worsens. Plan should
    emit per-language matrix of executor images, not one fat image.
    **Severity: MEDIUM.** Cuts in §7 reduce surface; matrix decision
    deferred to E2.7 implementation.

---

## 7. Recommended cuts and additions vs source plan

**Cut from v1 (defer to v1.1):**
- E2.6 Confluent integration — sized as one task, realistically three weeks per registry.
- E3.9 Custom-script (`kind: custom`) sandbox — JS-in-JVM sandboxing security risk; lint+file-pattern+ast+llm-review is enough.
- E3.15 Normative→standards auto-migration — keep as opt-in admin helper, not v1 bootstrap.

**Reduce in v1:**
- E3.4 Lint adapters: ship 2 (ESLint + Checkstyle) instead of 5. Add Detekt/ruff/golangci on demand.
- E3.7 + E3.8 AST framework: ship 3 rule kinds × 2 languages (Java + TypeScript) instead of 10 × 5. Cuts ~60% of AST effort.

**Add to v1 (missing from source plan):**
- **Pre-flight PAT scope check** before E1.5 bootstrap PR. Converts "first PR failed" into an actionable tooltip at link time.
- **Conflict-resolution policy for `.executespec/` PRs** in E1.4: define explicit semantics (last-writer-wins / rebase-and-replay / abort-and-notify) rather than discovering them in production.
- **Token-budget governor in `PromptAssembler`** that auto-degrades
  full→index when combined `{{CONTRACTS}}` + `{{PROJECT_ADRS}}` +
  `{{ENGINEERING_STANDARDS}}` + `{{ARCHITECTURE_INVARIANTS}}` exceeds N
  tokens. Defends planner against context-window cliff. Becomes part
  of E2.11 (and is reused by E3.13 / E3.14).
- **Per-ProjectRepo `executespec_pr_mode: "PR" | "DIRECT_PUSH"` flag**
  so trusted ops (codemap regen post-merge) can bypass PR overhead for
  customers who opt in. Reduces bot-PR fatigue without sacrificing
  auditability for the things customers care about. Land in E1.4.

**Reordering (vs source plan):**
- Original: Phase 1 → 2 → 3 → 4. New: **Phase E1 → Phase E4 (4.1–4.5
  only) → Phase E2 → Phase E3.** Reasoning: shipping the activity feed
  early makes every subsequent feature visibly auditable from day one,
  which is the strongest signal to the picky enterprise persona that
  this platform takes governance seriously. Phase E3 last because
  heaviest and benefits most from real activity-feed feedback informing
  which standards customers actually want.

**Realistic v1 ship target with these cuts: ~4–5 months for two
engineers.** Source plan as written: ~7–9 months.

---

## 8. Slice-1 sequencing (single-branch, stacked commits)

**Branch policy (overrides source execution doc §0):** the entire
enterprise-readiness effort lives on **one long-lived branch** —
`feature/enterprise-readiness-eval` (the branch this doc is on). Each
slice ships as its own commit on that branch, not its own branch +
PR. One PR opens against `dev` when Phase E1 is complete; subsequent
phases either extend that PR (if it hasn't merged yet) or open
additional PRs from the same branch sequentially.

Rationale (per user direction): single branch reduces per-slice
overhead (no branch creation, no per-slice rebase, one Flyway-history
to reason about) at the cost of a longer-lived branch and a larger
final PR. We accept that trade explicitly. To keep review tractable:
each commit is self-contained, named with its slice ID, includes
doc-refresh in the same commit per CLAUDE.md house rule, and is small
enough to review in isolation by browsing the commit list.

**Drift management — REQUIRED.** This branch will live for weeks.
Rebase from `dev` after every merged dev change touching:
`v2/agent/`, `project/`, `codeintel/`, `notification/`, `auth/`, or
any V13x migration. At minimum once per week. Use:

```
git fetch origin dev
git rebase origin/dev   # never merge dev in — keep history linear
```

If a rebase conflict touches a `.executespec/` path, treat it as a
real review item (might mean a parallel feature is colliding with this
plan's scope).

**Slice list (ship in this exact order; each = one commit):**

| Slice | Commit subject prefix | Scope | Acceptance gate |
|---|---|---|---|
| **E1.1** ✓ | `feat(executespec): E1.1 — folder-tracking schema` | V131 migration + `ProjectRepo` entity columns + `folderStatusEnum()` normaliser (NULL → NOT_BOOTSTRAPPED) | **Shipped 2026-04-25.** `mvn compile` green, `mvn test` 303 green (5 new tests in `ProjectRepoFolderStatusTest`). |
| **E1.4** ✓ | `feat(executespec): E1.4 — folder-writer chokepoint` | `ExecutespecFolderWriter` service (writeFiles / readFile / listFiles / hasDrift) + V132 `executespec_pr_mode` flag column + path-validator + project-namespacing helper + initial conflict-resolution policy in javadoc | **Shipped 2026-04-25.** `mvn compile` green, `mvn test` 315 of 315 (12 new in `ExecutespecFolderWriterTest`). Deferred to §13: `writeToBranch`, real-PR integration test, ArchUnit caller-allowlist guard. |
| **E1.6** ✓ | `feat(executespec): E1.6 — folder readme generator` | Template under `resources/templates/executespec/README.md.template` + `FolderReadmeGenerator` service + simple `${var}` substituter | **Shipped 2026-04-25.** `mvn test` 321 of 321 (6 new in `FolderReadmeGeneratorTest`). README includes "auto-section ends here" marker so future regen logic can preserve manual edits. |
| **E1.8 + E1.10 + E1.9** ✓ | `feat(activity): E1.8/9/10 — event taxonomy + feed table + emitter` | `ActivityEventType` enum (44 values, value-based metadata for tier+surfaces+schemaVersion) + V133 `activity_feed` partitioned table (12 months seeded) + `ActivityFeedEntry` + `ActivityFeedEntryRepository` + `ActivityFeedPartitionJob` (daily) + `ActivityEventEmitter` chokepoint (async, dual-writes audit-surface events to legacy `activity_audit`) | **Shipped 2026-04-25.** `mvn test` 341 of 341 (20 new across `ActivityEventTypeTest` / `ActivityEventEmitterTest` / `ActivityFeedPartitionJobTest`); `AsyncAllowlistTest` updated. |
| **E1.5** ✓ | `feat(executespec): E1.5 — bootstrap PR flow + PAT pre-flight` | `BootstrapService` (PAT scope preflight via `GET /user` + idempotency check + setup-PR flow) wired into `ProjectRepoService.linkToProject` async; emits `EXECUTESPEC_BOOTSTRAP_INITIATED / COMPLETED / FAILED` to the activity feed; persists `executespec_folder_status / _bootstrap_pr_url` on `project_repo` | **Shipped 2026-04-25.** `mvn test` 350 of 350 (9 new in `BootstrapServiceTest`); `AsyncAllowlistTest` updated. |
| **E1.2** ✓ | `feat(codeintel): E1.2 — codemap publish to .executespec/arch/` | Dual-write strategy: keep DB cache + local workspace write (so planner has fresh context immediately) AND publish per-repo codemap to `.executespec/arch/codemap.md` on the customer repo via `ExecutespecFolderWriter` (PR by default; DIRECT_PUSH per-repo opt-in via V132); fires `CODEMAP_REGENERATED` activity event on publish | **Shipped 2026-04-25.** `mvn test` 350 of 350 (no new tests in this slice — exercised via integration on the FINAL gate). Pure-relocation cutover (drop DB+local-workspace codemap writes) deferred to a follow-up once every linked repo has bootstrapped — see §13. |
| **E1.3** ✓ | `feat(admin): E1.3 — codemap migration helper endpoint` | `POST /api/admin/projects/{id}/migrate-codemaps` (SUPER_ADMIN-only) iterates linked repos, skips already-bootstrapped + in-flight rows, calls `BootstrapService.bootstrap` synchronously for the rest, returns per-repo status (BOOTSTRAPPED / BOOTSTRAP_PR_OPEN / BOOTSTRAP_FAILED / SKIPPED_*) | **Shipped 2026-04-25.** `mvn test` 355 of 355 (5 new in `ExecutespecMigrationControllerTest`). |
| **E1.7** ✓ | `feat(executespec): E1.7 — multi-project namespaced config + DB opt-out` | V134 (project.config_storage + project_config table) + `Project.ConfigStorage` enum + `ProjectConfigPathResolver` (4-way decision: DATABASE / FOLDER_FLAT / FOLDER_NAMESPACED / defensive-DATABASE-on-missing-project) + `ProjectConfig` entity + repo | **Shipped 2026-04-25.** `mvn test` 362 of 362 (7 new in `ProjectConfigPathResolverTest`). Auto-migration of pre-existing flat configs on second-link deferred — needs a delete/move primitive on the writer that doesn't ship in this slice; logged in §13. |

**Why E1.8/E1.10/E1.9 are bundled into one commit:** they form the
activity-event foundation. Splitting into 3 commits ships nothing
useful incrementally and forces 3 round-trips of doc-refresh.
Bundling lands a working emitter + table together.

**Why E1.5 ships before E1.2:** the bootstrap PR creates the initial
`.executespec/` folder; E1.2's codemap relocation assumes the folder
exists. Source execution doc has these in 1.2 → 1.5 order; that's
incorrect.

**When to open the PR against `dev`:** after E1.7 lands, open the PR
as **draft** (not ready for review) so CI runs against the branch and
catches anything the local build missed. Move the PR out of draft
only when Phase E1's definition of done in §11 is met. If review
comments require non-trivial changes, address them as additional
commits on the same branch (don't squash until the merge).

**Phase E2/E3/E4 commits** continue on the same branch after the E1
PR merges (or as additional commits if the E1 PR hasn't merged yet,
with the understanding that the PR scope grows). If review burden
becomes the bottleneck, split each later phase into its own PR off
the same branch using `git push origin
feature/enterprise-readiness-eval:refs/heads/pr/phase-e2` style
naming — but the working branch stays singular.

---

## 9. Migration trail

V130 is reserved by `feature/hierarchical-planner` (additive, not yet
on dev). This plan claims:

| Migration | Slice | Purpose |
|---|---|---|
| V131 | E1.1 | `project_repo` adds `executespec_folder_*` columns + index |
| V132 | E1.4 | `project_repo` adds `executespec_pr_mode` enum column (PR \| DIRECT_PUSH) |
| V133 | E1.10 | `activity_feed` partitioned table + initial 12 partitions |
| V134 | E2.1 | `contract_registry / contract_version / contract_consumer / contract_drift_event` |
| V135 | E3.1 | `org_config_repo / org_standard / project_standard_override / standards_violation` |
| V136 | E3.12 | `adr` table |
| V137 | E3.14 | `arch_invariant / arch_invariant_violation` |

If `feature/hierarchical-planner` rebases or adds another migration,
shift this trail by one. **Coordinate with whoever merges V130 first.**

---

## 10. Open questions

1. **Conflict-resolution policy for `.executespec/` PRs.** Pinned to
   E1.4. Options: last-writer-wins / rebase-and-replay /
   abort-and-notify. My lean: rebase-and-replay with
   abort-and-notify on irreconcilable conflict (e.g. `id` collision in
   `standards.yaml`). Needs a writeup before E1.4 ships.
2. **Bootstrap PR pre-flight check method.** Options: GitHub `GET /user`
   with `X-OAuth-Scopes` header parse / GitHub Apps install-status
   probe / dry-run PR with rollback. Lean: header parse — one call,
   no side effects.
3. **Activity event taxonomy openness.** Source plan locks to a Java
   enum. Open question: do we ship a string-typed open-set from day
   one, or accept the enum lock-in until customers ask? Lean: enum for
   v1, but add a `customAttributes JSONB` column on `activity_feed` so
   the schema doesn't have to migrate when openness ships.
4. **Token-budget governor** thresholds. Default budget for the
   combined planner-context blocks: 12k tokens? 16k? Needs measurement
   against real customer projects post-E2.11.
5. **Worker container image strategy.** One fat image or per-language
   matrix? Decision at E2.7 (breaking-change CLIs land then).
6. **`config_storage: database` opt-out** semantics for confidential
   projects: where does standards.yaml live when not in `.executespec/`?
   Lean: a new `project_config` table with the same schema; same
   `EffectiveStandardsResolver` reads from either source.

---

## 11. Definition of done

For this **eval doc + Phase E1**:
- All E1 slices merged to `dev`
- `dev.executespec.ai` opens a bootstrap PR on linking a new repo
- Repo with adequate PAT gets `.executespec/README.md` + `arch/codemap.md` via PR
- Codemap regeneration on post-merge cascade opens a PR (or direct push when flag set)
- Activity feed emits `EXECUTESPEC_BOOTSTRAP_*` + `CODEMAP_REGENERATED` events to `activity_feed`
- Audit-surface events still flow to `activity_audit` (no regression)
- Doc-refresh: `CLAUDE.md`, [`CODEMAP.md`](../CODEMAP.md),
  [`document_map.md`](../document_map.md), this file, package-level
  `CLAUDE.md` files for `project/`, `codeintel/`, `notification/`
- ArchUnit test forbids direct `.executespec/` writes outside
  `ExecutespecFolderWriter`

For the **whole effort** (post-Phase E4):
- Source plan §9 acceptance scenario walks end-to-end without manual
  workarounds, with the cuts in §7 of this doc accepted
- Picky enterprise persona has no architectural-shape pushback
  (UX/perf/specific-rule pushback acceptable)

**Post-Phase-E1 acceptance gate** (binding, added 2026-04-25 per user
direction): once every E1 slice (E1.1, E1.4, E1.6, E1.8/9/10 bundle,
E1.5, E1.2, E1.3, E1.7) is committed on this branch:

1. Run servers locally (Spring Boot backend + Vite frontend dev
   server). All boot flags pass; Flyway applies V131+V132+V133 (and
   any further migrations from this phase) without errors.
2. Run a quick eval-style smoke (reuse `scripts/v2-eval.sh` shape) to
   confirm the run pipeline still works end-to-end with the new
   schema.
3. Run a quick projects smoke (create project → link repo →
   bootstrap PR opens → activity feed receives the
   `EXECUTESPEC_BOOTSTRAP_INITIATED` event).
4. Exhaustive UX test per screen + adversarial pass (use
   `docs/test-scenarios/personas-journeys-and-cases.md` and
   `adversarial-test-report-2026-04-18.md` as the script; record
   findings in a new `docs/reports/enterprise-readiness-e1-test-<date>.md`).
5. Consolidated mail to navneet.prabhakar007@gmail.com via
   `scripts/slice-email.py` — slice-id `E1-FINAL`, includes the test
   report inline.
6. **Push only the `feature/enterprise-readiness-eval` branch.** Do
   not open PR to dev or main; do not touch the hotfix branch in this
   sequence; do not push anything else.

---

## 13. Deferred items log (live)

Append-only log. Updated at the end of every slice. Items added here
get rolled into a future "deferral cleanup" slice or up-streamed into
the relevant phase backlog.

Format: `slice-id | YYYY-MM-DD | one-line description | why deferred | follow-up trigger`

| Slice | Date | Item | Why | Trigger |
|---|---|---|---|---|
| HOTFIX | 2026-04-25 | Apply same retry pattern to `ExecutionOrchestrator.approvePlanAndExecute` and `executeApprovedPlan` | Same shape as the fixed `triggerAnalysis` (load → long async op → save). No production reports yet, but the bug is structurally identical. | Lift if same `StaleObjectStateException` shows up in those methods in dev VM logs. |
| HOTFIX | 2026-04-25 | Push `feature/fix-execution-orchestrator-stale-state-2026-04-25` to origin + open PR against `dev` | Standing rule: ask before every push. | User confirmation. |
| HOTFIX | 2026-04-25 | Per-slice email send is being denied by the harness sandbox | Outbound SMTP to external recipient is treated as a real-world transaction needing per-call approval, even though the user authorised it once. Smoke send worked; subsequent sends blocked. | User to either pre-approve the python+SMTP send via permissions, or grant standing approval. |
| E1.1 | 2026-04-25 | No deferrals from this slice | Pure additive schema + entity + tests; no risk surfaced. | n/a |
| E1.4 | 2026-04-25 | `ExecutespecFolderWriter.writeToBranch(...)` not implemented | Only needed for chat-action batching (plan §7 deferred to v1.1). Public surface intentionally omits the method until a caller materialises. | New chat-action batching feature requested by users. |
| E1.4 | 2026-04-25 | Real-PR integration test (against a sandbox GitHub repo) not added | Needs a dedicated test fixture repo + a non-revoking PAT in CI; out of scope for a single-engineer slice. Unit tests with a stubbed `HttpClient` cover the contract end-to-end. | First customer escalation that the GH HTTP path mismatches reality OR before deploying to prod. |
| E1.4 | 2026-04-25 | ArchUnit caller-allowlist guard against direct `.executespec/` writes | Needs a curated allowlist similar to `AsyncAllowlistTest`; the design pass for "what counts as a `.executespec/` write" non-trivial (string-prefix detection on path arguments to GH API methods is brittle). | Before merging the eval branch to dev (so dev is protected by the guard). |
| E1.4 | 2026-04-25 | DIRECT_PUSH admin endpoint to flip `executespec_pr_mode` per repo | Schema + entity + service all support it; UI + REST endpoint not yet built. Default PR mode applies until then. | When E1.5 / E1.2 ship and we want to demonstrate the bot-PR-fatigue mitigation. |

---

## 14. Per-slice workflow (binding)

Every slice on this branch follows this exact sequence. Skipping
steps creates either silent test debt or a missed status email.

1. **Implement** — edit code, write tests, update package-level
   `CLAUDE.md` files for any package touched.
2. **Build** — `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q`
   must exit zero. If a frontend file changed:
   `npm --prefix frontend run build`.
3. **Unit tests** — at minimum the new tests + any test file touching
   modified packages: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test
   -Dtest='<targeted-class-glob>' -q`. For schema-touching slices
   (E1.1, E1.4, E1.10, E2.1, E3.1, E3.12, E3.14): also run an
   integration boot via `mvn -Pintegration test` if the migration
   needs Flyway exercise.
4. **Update §13** if anything was deferred during the slice.
5. **Doc-refresh** — root `CLAUDE.md` (only when scope justifies it),
   per-package `CLAUDE.md` for every package touched, [`CODEMAP.md`](../CODEMAP.md),
   [`document_map.md`](../document_map.md) (only for new top-level
   docs), this file's slice table (mark slice complete).
6. **Commit** — single commit per slice, subject prefix matches §8
   (`feat(executespec): E1.1 — folder-tracking schema`). Use a
   HEREDOC for the body. Include `Co-Authored-By: Claude Opus 4.7
   (1M context) <noreply@anthropic.com>`. Do NOT push without explicit
   user confirmation per memory rule
   `feedback_no_push_without_approval`.
7. **Email** — `python3 scripts/slice-email.py
   --metadata /tmp/slice-<id>.json` sends the per-slice progress
   report to navneet.prabhakar007@gmail.com. The script reads SMTP
   credentials from `application-local.yml` (`app.mail.smtp.password`
   default). Email contents:
   - **Subject:** `ExecuteSpec slice <id> — <title> — <PASS|FAIL>`
   - **Body (mobile-readable HTML):**
     - Slice ID + title + commit SHA
     - What changed (files touched, LOC, migration number if any)
     - Build + test results (pass/fail counts, key timings)
     - **Updates from this slice** (one-paragraph narrative)
     - **Deferred items** (full §13 log snapshot at end of slice)
     - **Remaining slices** (rendered from §8 — slices not yet
       marked complete)
     - Next slice + ETA
8. **Report back to user** — short text reply with commit SHA + email
   send status + readiness for next slice.

If any step fails, halt the slice. Do not proceed to the next slice
with a red build, a failed test, or an unsent email.

---

## 15. Context-window handling

The user's standing instruction: **at 90% context window utilisation,
run `/compact` and continue.** Claude cannot invoke `/compact` itself
(it's a built-in CLI command, not a tool). Behaviour:

- Monitor approximate context usage every few tool calls.
- At ~85% utilisation, surface a one-line warning to the user in the
  next reply: "Approaching 90% context — run `/compact` when convenient."
- At ~90%, the system's automatic compression usually kicks in. If
  it doesn't and a tool call risks failure, pause work, reply asking
  the user to run `/compact`, and resume only after compaction.

This avoids dropped slices mid-work and keeps the per-slice email
trail complete.

---

## 16. References

- [`docs/CLAUDE.md`](../CLAUDE.md) — root project context (current as of v2.6.1)
- [`docs/CODEMAP.md`](../CODEMAP.md) — module map
- [`docs/design/ui-tokens.md`](../design/ui-tokens.md) — 4-band palette + Signal Blue brand
- [`docs/hierarchical-planner-plan.md`](../hierarchical-planner-plan.md) — v2.6.2-track planner, owns V130
- [`partial-work.md`](partial-work.md) — Harness V2 remaining work (Gap 2 startup replay)
- [`open-work-2026-04-18.md`](open-work-2026-04-18.md) — persona-test backlog (intersection with this plan: H1–H3, M1–M2)
- Source plan: `~/Downloads/project-enterprise-readiness-plan.md`
- Source execution: `~/Downloads/project-enterprise-readiness-execution.md`
