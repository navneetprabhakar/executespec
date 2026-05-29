# Audit: `standards/` — org engineering-standards enforcement (enterprise readiness)

Status: **DONE** · Updated 2026-05-29 · 26 files / 2,573 lines · all read in full

## Subpackages
- **root** (8) — `StandardsRunner(149)` orchestrator + `EffectiveStandardsResolver(204)` merge + `StandardsPromptComposer(53)` + `OrgStandardController(173)` + entities `OrgStandard(53)`/`ProjectStandardOverride(76)`/`OrgConfigRepo(56)`/`StandardsViolation(72)` ✓
- **`model/`** (2) — `Standard(97)` (sealed `Enforcement` interface + 4 records) + `StandardsParser(252)` ✓
- **`enforcement/lint/`** (6) — `LintProcessRunner(63)` + `CheckstyleAdapter(126)` + `EslintAdapter(103)` + `LintAdapter(29)`/`LintResult(25)`/`LintViolation(13)` ✓
- **`enforcement/ast/`** (1) — `AstCheckEnforcer(274)` · **`enforcement/filepattern/`** (1) — `FilePatternEnforcer(89)` · **`enforcement/llmreview/`** (1) — `LlmReviewEnforcer(119)` ✓
- **`override/`** (2) — `StandardsOverrideController(234)` + `OverridePatternDetector(73)` ✓ · **`report/`** (1) — `StandardsComplianceController(157)` ✓
- **repos** (4) — `OrgStandard(15)`/`ProjectStandardOverride(17)`/`StandardsViolation(39)`/`OrgConfigRepo(12)` ✓

## What it does
Org-defined coding standards enforced during runs (plan E3.x). An ORG_ADMIN defines standards as YAML (`org_standard`, 4 enforcement strategies: **lint** / **file-pattern** / **ast-check** / **llm-review**); a project can downgrade-or-exclude them (`project_standard_override`, audit-logged, ≥30-char rationale). `EffectiveStandardsResolver` merges org+project into the effective set; `StandardsRunner` runs the 3 deterministic strategies server-side (lint via CLI adapters, AST via regex, file-pattern via glob) while llm-review piggy-backs the reviewer's prompt. Violations land in `standards_violation`; a compliance report + recurring-override detector round it out.

## Headline: same profile as `contracts/` — high quality, wired into RunOrchestrator behind a default-OFF flag.

### Live-wiring status
- 🔵 **`StandardsRunner` IS wired into `RunOrchestrator`** behind `featureFlagService.isEnabled("standards.enforcement.enabled")` ([RunOrchestrator.java:1542](../../../src/main/java/com/navneet/executespec/integration/RunOrchestrator.java)), default **OFF** (V212). The plan calls for it to run twice — worker self-check + reviewer pass (defence-in-depth). Dormant-by-flag, not orphaned (same as [contracts.md](contracts.md)). The deterministic enforcers run server-side; `LlmReviewEnforcer.composePromptBlock` + `StandardsPromptComposer.compose` produce the `{{LLM_REVIEW_STANDARDS}}` / `{{ENGINEERING_STANDARDS}}` fragments the agents see (injected programmatically — these placeholders are not in committed `prompts/*.md`, consistent with runtime composition).
- 📝 Two pieces explicitly **not wired**, acknowledged in-code: `OverridePatternDetector` (recurring-override → "make it a permanent exception" recommendation) is "designed to be called by a future `@Scheduled` job" with no caller yet; and the AST checker is regex-based with a §13 deferral to a real parser (JavaParser/ts-morph).

### Engineering quality (🟢 — matches the contracts bar)
- 🟢 **Safe YAML parsing.** `StandardsParser` uses `SafeYamlLoader.load` (not raw SnakeYAML `Yaml.load`), so a malicious/over-clever standards.yaml can't trigger arbitrary-type deserialization. It also **collects every validation error in one pass** (no silent skips) and validates each enforcement type's required fields. Good security + good UX.
- 🟢 **Clean strategy patterns, all pure/stateless + degrade gracefully.** `Standard.Enforcement` is a sealed interface (4 records); `LintAdapter`/`AstCheckEnforcer`/`FilePatternEnforcer` take callbacks/paths so they're unit-testable cold. Every CLI adapter (`EslintAdapter`/`CheckstyleAdapter`) and `LintProcessRunner` return `UNAVAILABLE` (never throw) when the CLI isn't on PATH — a future deploy that installs the CLI activates the adapter with no code change. `LintProcessRunner` uses `ProcessBuilder(argv)` (no shell) + bounded timeout + `destroyForcibly`. `FilePatternEnforcer` + `AstCheckEnforcer.dependencyDirection` reuse `contracts/DetectorUtils.matchesGlob` — nice cross-package consistency.
- 🟢 **The override model is correctly safety-biased.** `EffectiveStandardsResolver` only allows severity *downgrades* (BLOCKING→WARNING→ADVISORY); upgrades are rejected and logged; `org_level_locked` standards ignore project overrides (logged). Constructor injection throughout; forward-compat String enums (`overrideTypeEnum()`/`storageModeEnum()` fall back). `OrgStandardController` uses declarative `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")` + an org-scope check.

### Security / tenancy
- 🟡 **`StandardsComplianceController` can leak another org's standards.** `GET /api/projects/{projectId}/standards/report?orgId=<X>` guards only on `projectId` (`assertCanRead`) but feeds the caller-supplied `orgId` straight into `resolver.resolve(orgId, …)` → `orgStandardRepo.findByOrgId(orgId)`. A caller with read access to *any* project can pass an arbitrary `orgId` and read that org's standard YAML bodies + their descriptions/rationales. Low severity (needs a readable projectId; standards aren't usually secret) but it's a real cross-tenant info leak — the `orgId` should be derived from the project's `orgId`, not trusted from the request param. Worth fixing before the flag flips on.
- 🟡 **Per-violation override role-gate is a TODO.** `StandardsOverrideController` docstrings say emergency override / project exception should be PROJECT_ADMIN/ORG_ADMIN-gated "(controller-side TODO; advisory in v1)" — today they only require `assertCanWrite` (any in-org writer). So any DEVELOPER can override a BLOCKING standard with a 30-char rationale. Same coarse-write theme as [project.md](project.md)'s `ProjectAccessGuard`. The ≥30-char rationale + activity-event audit trail mitigate (it's logged, not silent), but least-privilege isn't enforced.
- 🟢 Override/exception writes correctly hardened to `assertCanWrite` (audit 2026-05-12 B#14 — emergency override previously had *no* access guard at all). Rationale mandatory (≥30 chars) + every mutation emits a `STANDARDS_*` activity event.

### Tech-debt (minor)
- 📝 `OrgConfigRepo.StorageMode.REPO` (standards-as-code in a dedicated GitHub config repo) is modelled but DATABASE is the only v1 path — the REPO mode's folder-fetch wiring is deferred (the compliance report's own comment: "project-level standards.yaml not loaded here in v1").
- 🟢 No stale-OPENAI default (this package makes no direct LLM call — llm-review only *composes a prompt fragment* for the existing reviewer call + parses its JSON back robustly).

## Cross-refs
Wired (flag-off) at → [integration.md](integration.md) (`RunOrchestrator`, `standards.enforcement.enabled`); enforcement feeds the worker self-check + reviewer ([reviewer.md](reviewer.md)) passes. Sibling enterprise-readiness track (identical dormant-by-flag profile) → [contracts.md](contracts.md). Glob util shared from → [contracts.md](contracts.md) (`DetectorUtils`). Override/exception PRs + activity → `activity/` (Tier 7). Tenant gate → [project.md](project.md) (`ProjectAccessGuard`); the cross-org `orgId`-param leak is the inverse of that guard's discipline. Violations stored → `standards_violation` (this pkg). ADR cross-links (`relatedAdrs`) → `adr/` (next).
