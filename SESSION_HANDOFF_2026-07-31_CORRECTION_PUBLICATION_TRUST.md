# Session Handoff — Correction / Publication Trust Task

**Date:** 2026-07-31  
**Repository:** `/Users/navneetprabhakar/Documents/antigravity/executespec`  
**Environment:** `https://dev.executespec.ai`  
**Scope:** the current correction, continuation, validation, brownfield-contract, evidence-freshness, publication-admission, and dev-deployment task.  
**Excluded:** the broader Projects + AIDLC persona/E2E programme. It is listed only as a follow-up dependency, not as completed work.

## Executive state

The current task’s code work is merged to `dev` and the repaired build is deployed successfully to dev.

- Main trust batch: PR [#301](https://github.com/navneetprabhakar/executespec/pull/301), merged as `0e67f75fdbbee3b970265d8b517bb87516ff5da7`.
- PR #301 deployment: [run 30619391243](https://github.com/navneetprabhakar/executespec/actions/runs/30619391243). It failed during health check because V369 schema and constructor regressions prevented application boot.
- Boot-repair batch: PR [#302](https://github.com/navneetprabhakar/executespec/pull/302), merged as `5daa46f69f8b113d914a50787ee22919edb079be`.
- Successful dev deployment: [run 30629663577](https://github.com/navneetprabhakar/executespec/actions/runs/30629663577).
- Live verification: `GET https://dev.executespec.ai/actuator/health` returned HTTP `200` with `{"status":"UP"}`.
- Current remote `dev`: `5daa46f69f8b113d914a50787ee22919edb079be`.
- No production deployment, npm publish, CLI release, or VS Code extension change was performed.

This task is implementation-complete and dev-deployed. The unrelated-repository Projects + AIDLC E2E remains unperformed.

## Trust boundary being repaired

The issue was deterministic correctness, not prompt quality. The system could mutate code, spend on a different model route, report evidence from another workspace version, or publish a regressed brownfield result while appearing successful.

The implementation now treats these as deterministic publication prerequisites:

1. Approved provider/model/action/proposal identity equals the route physically used by worker and reviewer.
2. Protected brownfield HTTP contracts remain unless a valid breaking-change decision authorises removal.
3. Correction output is evaluated transactionally against a pre-correction checkpoint.
4. Build, test, reviewer, workflow/config, endpoint, and score claims are bound to the current evidence generation.
5. Stale, incomplete, mismatched, or regressive evidence cannot reach SCM publication or deployment.

## Timeline and state transitions

### Initial trust-boundary batch

Branch: `codex/e2e-durable-publication-trust`, created from `origin/dev`.  
Commit: `b02143393 Harden correction recovery and publication evidence`.

The commit changed 77 files across production code, migration V369, focused tests, and in-repository package documentation. It was pushed once and merged as PR #301.

### Pre-merge evidence for PR #301

- Java 21 clean verification: `6,393` tests, `0` failures, `0` errors, `0` skips.
- Focused trust/concurrency cohort: `184` tests passed.
- Frontend build and Prettier check: passed.
- Frontend ESLint: `0` errors; six pre-existing warnings in `IdeProjects.agentHandoff.test.tsx`.
- Frontend Vitest: `83` files and `533` tests passed.
- Architecture ceilings: passed. WorkerAgent `3415`, RunOrchestrator `4322`, AutomaticCorrectionLifecycle below the 600-line new-class ceiling.
- `git diff --check`: passed.
- [Frontend CI](https://github.com/navneetprabhakar/executespec/actions/runs/30616603952/job/91111136531): passed.
- [Java CI](https://github.com/navneetprabhakar/executespec/actions/runs/30616604367/job/91111137981): passed.

### First post-merge deployment failure

[Build & Deploy run 30619391243](https://github.com/navneetprabhakar/executespec/actions/runs/30619391243) completed frontend/JAR build, checksum, upload, GCE deployment, and startup wait, then failed health check.

Root causes:

- V369 created digest columns as PostgreSQL `CHAR(64)`/`bpchar`.
- `CorrectionAttempt` mapped those fields as `VARCHAR(64)`.
- Hibernate schema validation rejected the mismatch on boot.
- A second defect was then exposed: `StartupRunResumer` had a public test-compatibility constructor beside Lombok’s required-arguments constructor, with no explicit Spring constructor selection.

CI was green because it did not boot Hibernate against the migrated production-shaped schema.

### Boot-repair batch

Branch: `codex/fix-v370-correction-attempt-varchar`.  
Commits included `cccde4d81 fix: repair dev boot crash loop from V369 correction-attempt schema` and `688da07fe test: pin V370 in the exact Flyway migration inventory`.

PR #302 passed frontend lint and Java Spotless/unit-test CI. Its recorded local evidence included a real PostgreSQL Flyway replay from V353 through V370, clean local Spring Boot startup in 22 seconds, health HTTP 200, and focused 17/17 tests.

[Successful deployment run 30629663577](https://github.com/navneetprabhakar/executespec/actions/runs/30629663577) completed frontend build, JAR build, upload, GCE deployment, startup, health check, provider-key verification, cleanup, and deployment summary.

V370 changes these V369 digest columns in place, preserving their 64-character hexadecimal values and existing checks/index relationships:

- `before_generation`
- `after_generation`
- `evidence_workspace_generation`
- `evidence_snapshot_digest`
- `proposal_digest`

V369 remains immutable; the forward migration is the only schema repair. The constructor repair uses an explicit `@Autowired` production constructor and a package-private test overload, matching the established `RemoteToolDispatcher` pattern.

## Implementation details

### Exact approved-route fencing

The original failure was approval of `openrouter/x-ai/grok-4.20` followed by worker execution on `openrouter/deepseek-v4-pro`, while reviewer/cost evidence could describe the requested route.

Implemented:

- Approval binds run, task, action, provider, model, proposal version, proposal digest, and approval ID.
- The exact route crosses the continuation/resume boundary.
- Exact continuation bypasses initial routing, tier-model replacement, adaptive routing, and cross-model failover for the approved target task.
- Same-route transient retries remain bounded and allowed.
- Unavailable approved routes stop before provider spend; no silent substitution.
- Physical provider-call ledger evidence is checked before worker attribution, reviewer execution, validation, and publication attestation.
- Worker route, reviewer route, and attributed usage must match the approved route.
- Mismatch blocks/rolls back and prevents reviewer, validation, attribution, and final publication.
- The fence is task-scoped and does not pin unrelated fresh tasks.

Relevant surfaces: `RunLifecycleService`, `RunOrchestrator`, `SequentialExecutor` continuation requests, `RunEffectiveRouteService` attempt fences, `ApprovedContinuationRouteAttestation`, and publication admission.

### Brownfield HTTP contract preservation

The original defect allowed `POST /simulation/casino-game` to disappear while generated `/api/casino-game` code and self-authored tests looked healthy.

Implemented:

- Brownfield admission captures a canonical public HTTP surface snapshot.
- Spring extraction combines servlet context path, class-level mappings, method mappings, method arrays, slash normalization, and source locations.
- Preservation requirements resolve into exact immutable route constraints and a digest-bound evidence record.
- Comparison runs after worker completion, after correction, during final validation, and immediately before SCM publication.
- A new alias is allowed only when the original route remains available.
- Replacement-only route changes fail deterministically.
- Explicit valid breaking-change evidence is required to authorise removal.
- Compatibility failure prevents SCM publication.

Key files: `contractsurface/SpringHttpSurfaceExtractor.java`, `contractsurface/SpringContractSourceInventory.java`, `integration/ValidationReviewIssueSynthesizer.java`, and `integration/scm/PublicationAdmissionService.java`.

### Transactional correction and rollback

Correction now follows:

```text
pre-correction checkpoint
  → durable correction attempt admission
  → isolated constrained mutation
  → protected-contract comparison
  → build/test/workflow/endpoint checks
  → read-only reviewer
  → evidence regeneration
  → accept and publish, or restore checkpoint
```

Implemented:

- `CorrectionAttempt` persistence records attempt identity, approval identity, phase, owner, workspace generations, evidence digest, proposal digest, and outcome.
- V369 creates the durable correction-attempt foundation; V370 converts five digest columns from `CHAR(64)` to `VARCHAR(64)` without rewriting immutable V369.
- Code mutation invalidates prior build, test, review, coverage, workflow, and contract evidence.
- Evidence is bound to workspace/diff/content SHA and report snapshot digest.
- Acceptance requires deterministic validation, successful build/test evidence, protected-contract compatibility, reviewer quiescence, and exact route evidence.
- Regressive, rejected, stale, mismatched, or incomplete corrections restore the pre-correction checkpoint.
- Rejected corrections cannot reach SCM publication.
- Recovery is owner/phase CAS-fenced and can finalize only an exact completed receipt with a fresh, complete, build-passed, execution-verified, all-tests-green report.
- Recovery reasserts the database lease between repository rollback, evidence restore, task restore, and proof writes.
- Git/index mutation (`git add`, `write-tree`, reset/restore) uses the authority fence; generation/diff reads are fenced too.
- Database workspace authority is paired with a host-local OS file lock.
- Lease close is synchronized and idempotent.
- Recovery scan isolates authority-acquisition failures.
- Automatic rollback closes/removes prepared authority even if evidence persistence fails.
- Worker correction operations use `WorkerCorrectionToolFence`.

Key files: `integration/DurableCorrectionLifecycle.java`, `integration/CorrectionAttempt.java`, `integration/CorrectionAttemptRepository.java`, `integration/RunWorkspaceAuthorityService.java`, `integration/CorrectionWorkspaceTransaction.java`, `integration/CorrectionStateSnapshots.java`, `integration/CorrectionActionJson.java`, and `engine/WorkerCorrectionToolFence.java`.

### Validation provenance and score parity

Implemented:

- Validation claims carry provenance, confidence, workspace/evidence generation, and source/command support.
- LLM-assisted endpoint/spec evidence can warn, reduce confidence, or cap a score, but cannot masquerade as deterministic failure.
- Explicit deterministic false remains a hard failure.
- Missing/unavailable build evidence is `UNAVAILABLE`/`UNKNOWN`, not an invented failed build.
- Workflow-only requirements do not synthesize endpoint requirements.
- Non-API projects can classify endpoint coverage as not applicable.
- Score-zero results retain exact deterministic gate causality.
- Functional-proof ceilings are consistent across live generation, projection, rescoring, and backfill.

Key files: `report/ValidationAccuracyFacts.java`, `reviewer/AccuracyEvidenceCitationPolicy.java`, `report/ValidationEndpointCoverageEvidence.java`, `report/ValidationReportService.java`, `report/ValidationReportScoreCalculator.java`, `report/ValidationReportScoreProjection.java`, `report/VerifiedScoreBackfillService.java`, and `scoring/ReviewScoringService.java`.

### Workflow/config evidence and command resolution

- `.github/workflows/*.yml` and `.yaml` are included in a bounded evidence lane.
- YAML is parsed safely and prioritized using workflow/CI/action/deployment terminology.
- Precise path-segment exclusions replace broad substring exclusions; `build.yml` and `test.yml` are not discarded because of their names.
- `target/`, `build/`, and `dist/` remain bounded/excluded where appropriate.
- A usable `./mvnw` is preferred for Maven build and test resolution.
- Build/test evidence persists exact command, working directory, exit code, and bounded output.

### Publication admission and diagnostics

Before SCM push, PR creation, or deployment, admission checks exact route compliance, lease ownership, workspace-generation freshness, protected-contract compatibility, explicit build/test status, correction acceptance, and evidence freshness/non-regression.

Admission is held through irreversible SCM/deployment operations. Publication failures include eligibility state, blocking gate, error code, human-readable reason, and safe remediation/retry action.

Run sync, SCM push, execution-branch publication, and deployment packaging are fenced against concurrent workspace mutation. Deployment rechecks workspace generation after Dockerfile preparation.

## Tests and verification

Final local evidence:

- Backend clean verify: `6,393` tests, `0` failures, `0` errors, `0` skips.
- Focused trust/concurrency cohort: `184` tests passed.
- Frontend: `83` files / `533` tests passed.
- Frontend build: passed.
- Frontend format check: passed.
- Frontend lint: `0` errors and six pre-existing warnings.
- Architecture ceilings and `git diff --check`: passed.

Important added/extended tests cover:

- exact route mismatch, unavailable route, task scoping, physical worker/reviewer evidence, and attribution;
- Spring context paths, prefixes, arrays, normalization, aliases, replacements, and breaking-change evidence;
- correction evidence invalidation, accepted correction, regressive rollback, reviewer workspace mismatch, and publication refusal;
- workflow selection, generated-directory exclusion, Maven wrapper selection, unavailable vs failed build evidence;
- durable correction recovery, lease acquisition/expiry/close, CAS ownership, recovery scan isolation, and fenced Git/index operations;
- deployment/sync/SCM admission and post-packaging generation re-attestation;
- live/backfill/projection score caps and score-zero causality;
- V369/V370 migration inventory and schema contract.

## Current repository state

- `origin/dev` was fetched and points to `5daa46f69f8b113d914a50787ee22919edb079be`.
- Local branch: `codex/fix-v370-correction-attempt-varchar`.
- Local tip `688da07fe` is an ancestor of merged `origin/dev`; no unmerged code remains relative to dev.
- Only these user-owned untracked paths remain and were intentionally untouched/staged nowhere:

  - `.codex-work/`
  - `SESSION_HANDOFF_2026-07-28_ENTERPRISE_DEMO.md`
  - `docs/gtm/`

Do not run `git add -A` in this checkout. For future work, fetch first and branch from current `origin/dev` only.

## Remaining work / handoff boundary

### Completed for this task

- Correction lifecycle hardening is merged.
- Exact approved-route fencing is merged.
- Brownfield contract preservation is merged.
- Validation provenance and score parity are merged.
- Publication admission and SCM/deployment fencing are merged.
- V369/V370 boot repair is merged.
- Dev deployment is healthy.

### Not proven by this task

1. **Projects + AIDLC dev E2E:** no unrelated GitHub repository journey was exercised here. A separate task must create/connect/analyse a non-ExecuteSpec repository and exercise project architecture/docs/agent/history/search, brownfield generation/correction, and branch → PR → CI → deliberate failure → fix → CI success → deployment → runtime smoke with persisted evidence.
2. **Authenticated feature smoke:** public health proves process health only; it does not prove authenticated project operations, plan approval, correction admission, publication, or deployment UI flows.
3. **CLI validation:** no CLI code changed and no CLI release/E2E was performed.
4. **Sibling docs repository:** migration/reason-log cross-links in `executespec-docs` were outside the explicit two-repository scope and were not modified.
5. **Production release:** no production deployment, npm publish, CLI release, or VS Code extension release was authorised or performed.

## Recommended next action

Treat this current task as a completed dev trust-boundary slice. Start the independent authenticated Projects + AIDLC E2E next, using a deliberately unrelated GitHub repository and preserving API/UI/event/GitHub/deployment evidence for every journey. Do not create another clone or worktree.

## Useful commands

```bash
cd /Users/navneetprabhakar/Documents/antigravity/executespec
git fetch origin dev --prune
git status --short --branch
git log -1 --oneline origin/dev
curl -fsS https://dev.executespec.ai/actuator/health
gh pr view 301 --web
gh pr view 302 --web
gh run view 30629663577 --web
```

For future server code:

```bash
git switch -c codex/<short-description> origin/dev
```

---

# Master Goal Appendix

This appendix expands the narrow technical handoff above into a complete handoff for the pasted goal and the later discussion items. It deliberately distinguishes implementation evidence from unperformed dev E2E evidence.

## Status vocabulary

- **FIXED / VERIFIED:** implementation exists and the relevant automated or runtime evidence was collected.
- **IMPLEMENTED — E2E NOT PROVEN:** code/tests indicate support, but the requested dev journey was not exercised.
- **PARTIALLY FIXED:** one layer is addressed, but an important path or proof is missing.
- **NOT TESTED:** no reliable evidence was collected for the requested journey.
- **PRODUCT DECISION PENDING:** the request requires an explicit product decision rather than an inferred implementation.
- **OUT OF SCOPE / FUTURE:** intentionally not part of this release slice.

## Requirement traceability against the pasted goal

| Requirement | Current status | Evidence / gap |
|---|---|---|
| Exact approved provider/model/action route cannot silently change | **FIXED / VERIFIED** | PR #301, route-fence tests, physical worker/reviewer ledger checks, publication attestation. |
| Brownfield public HTTP contracts cannot disappear silently | **FIXED / VERIFIED** | Spring surface inventory, contract digest, post-worker/correction/validation/publication gates, regression tests. |
| Correction is checkpointed, validated, accepted or rolled back | **FIXED / VERIFIED** | Durable correction lifecycle, V369/V370, CAS/lease/file-lock fencing, 184-test trust cohort. |
| LLM-only evidence cannot masquerade as deterministic failure | **FIXED / VERIFIED** | Citation/provenance policy, score caps, deterministic-vs-LLM evidence tests. |
| Stale evidence cannot be published | **FIXED / VERIFIED** | Workspace generation, report snapshot, route, contract, lease, and publication admission checks. |
| Workflow YAML evidence lane | **IMPLEMENTED — E2E NOT PROVEN** | Bounded workflow evidence and focused tests exist; no authenticated dev project journey has exercised it. |
| `build.yml` / `test.yml` must not be discarded by filename | **FIXED / VERIFIED locally** | Precise path-segment filtering and evidence tests; no dev E2E proof. |
| Generated `target/`, `build/`, `dist/` exclusions | **FIXED / VERIFIED locally** | Bounded evidence rules and tests. |
| Maven wrapper parity for build and test | **IMPLEMENTED — E2E NOT PROVEN** | Resolver support and local tests; no unrelated-repository dev run using an actual `mvnw`. |
| Preserve Gradle-wrapper behavior | **IMPLEMENTED — E2E NOT PROVEN** | No regression evidence from an unrelated Gradle project. |
| Persist exact command, directory, exit code, bounded output | **IMPLEMENTED — E2E NOT PROVEN** | Contract exists; dev project evidence still missing. |
| Structured SCM diagnostics | **IMPLEMENTED — E2E NOT PROVEN** | Admission/publication error model exists; no live push denial/permission failure journey recorded. |
| Generated-document regeneration reliability | **PARTIALLY FIXED** | Deadlines and bounded operation paths exist; the reported dev regeneration journey was not rerun and no persisted success/failure evidence is attached. |
| Architecture/map refresh reliability | **PARTIALLY FIXED** | Timeout/deadline handling exists; the reported timeout case was not rerun end to end on dev. |
| Web and CLI separate task facts from verified outcome | **PARTIALLY FIXED** | Web wording and run-result evidence were corrected; complete web + CLI presentation audit was not performed. |
| Correction visibility: checkpoint, diff, invalidation, delta, outcome, reason | **IMPLEMENTED — E2E NOT PROVEN** | Backend evidence model and UI-facing data exist; no full dev UI walkthrough is recorded. |
| Score-zero deterministic causality | **FIXED / VERIFIED** | Exact score-zero causal evidence and backfill/projection parity tests. |
| No unsafe output reaches SCM | **FIXED / VERIFIED** | Publication admission and protected-contract/route/evidence gates. |

## Later product issues discussed after the pasted goal

These are tracked separately from the merged correction/publication slice.

| Later item | Status | What is known |
|---|---|---|
| Model matrix per task | **IMPLEMENTED — E2E NOT PROVEN** | YAML matrix, agent-tier routing, org overrides, and continuation candidates exist; no complete dev project/run matrix journey was recorded. |
| Set model in web agent | **IMPLEMENTED — E2E NOT PROVEN** | Web agent/model-routing surfaces exist; no authenticated dev smoke evidence is attached. |
| Web-agent files truncating around 110–150 lines | **PARTIALLY FIXED / NOT REPROVEN** | Read/output bounds and explicit truncation handling were hardened; the exact “simple web UI todo app” reproduction across multiple models was not rerun and is not proven fixed. |
| Link/create project inside web agent | **PRODUCT DECISION PENDING** | Existing Projects functionality may be sufficient; the required web-agent affordance and navigation decision remains unresolved. |
| Close current workspace | **NOT TESTED / PRODUCT DECISION PENDING** | No final interaction contract or dev evidence was captured. |
| Workspace chat snapshot/history | **PARTIALLY IMPLEMENTED / PRODUCT DECISION PENDING** | Chat/run history mechanisms exist, but the requested snapshot-only semantics and UX trade-off were not closed with a product decision. |
| OpenRouter key configuration at `/provider-keys` | **IMPLEMENTED — E2E NOT PROVEN** | OpenRouter organization credential route exists; no authenticated dev save/read/denial evidence is recorded here. |
| Regenerate docs failure at `/projects/.../code` | **PARTIALLY FIXED / NOT REPROVEN** | Operation deadlines and reliability work exist; the exact project URL was not rerun after deployment. |
| Refresh map timeout | **PARTIALLY FIXED / NOT REPROVEN** | Bounded operation/deadline handling exists; no successful retry and persisted operation-state evidence is recorded. |
| Compare History must diff file contents, not only detect changes | **IMPLEMENTED — E2E NOT PROVEN** | Architecture content diff path exists; no dev UI walkthrough with a known two-version file diff was captured. |
| Semantic indexing unavailable / lexical fallback | **IMPLEMENTED — E2E NOT PROVEN** | Governed embedding capability and honest lexical degradation exist; both available and degraded modes remain to be exercised in dev. |
| New-project project-feature test | **NOT TESTED** | No complete create-project → connect-repo → analyse → architecture/docs journey is recorded. |
| Timeline showing `0 ms` and inflated duration | **PARTIALLY FIXED / NOT REPROVEN** | Timestamp filtering/normalization was changed; the supplied run-details screenshot scenario was not rechecked on dev. |
| AI DLC: codegen → PR → CI → fix CI → deploy → track deploy | **PARTIALLY IMPLEMENTED / E2E NOT PROVEN** | Backend/web surfaces exist, but the complete chained journey was not executed in this task. |
| PilotSRE/DevOps agent with PagerDuty/New Relic/A2A | **OUT OF SCOPE / FUTURE** | No implementation or release claim belongs in this handoff. |

## Persona and product-prodigy testing requested later

The requested three-hat review was: Tech Prodigy, Product Prodigy, and UX Prodigy, first for an individual persona and then for a principal persona, limited to code generation and project functionality. This was not completed in the current task.

Required future evidence should include, for each persona:

- task framing and success criteria;
- project creation/linking and repository understanding;
- model/provider selection and route visibility;
- code generation completeness and file truncation checks;
- correction proposal, approval, rollback, and evidence freshness;
- architecture/map/docs/history/search behavior;
- build/test/PR/CI/deploy facts;
- negative-path behavior and actionable diagnostics;
- product, technical, and UX findings with priority and reproducible evidence.

The current handoff must not be read as a persona audit or enterprise-readiness sign-off.

## Projects + AIDLC dev E2E: complete journey matrix

The requested E2E was to use a GitHub repository unrelated to ExecuteSpec. `navneetprabhakar/montecarlo` and project ID `f97ed102-f695-4a83-a1f4-a92b325e610c` were identified as a candidate in the surrounding work, but no complete evidence-backed journey was executed for this handoff.

| Journey | Positive case required | Negative case required | Current result |
|---|---|---|---|
| Project creation | Create a new project | Duplicate/idempotent create | **NOT TESTED** |
| Repository connection | Connect unrelated GitHub repo with PAT | Invalid PAT / insufficient permission | **NOT TESTED** |
| Initial analysis | Analyse default branch and persist result | Clone/analysis timeout and retry | **NOT TESTED** |
| Code map | Generate/read map | Refresh timeout and retry | **NOT TESTED** |
| Architecture | Generate HLD/LLD and cross-repo architecture | Architecture generation failure | **NOT TESTED** |
| Generated docs | Generate/download explanation/setup docs | Regeneration failure and bounded retry | **NOT TESTED** |
| Project agent grounding | Ask grounded repository/project question | Missing/ambiguous source evidence | **NOT TESTED** |
| History comparison | Compare actual architecture/file content diff | No diff / unauthorized version | **NOT TESTED** |
| Semantic search | Embedding-backed search | Provider unavailable → honest lexical mode | **NOT TESTED** |
| Workspace lifecycle | Open, use, and close/return from workspace | Stale workspace / missing history | **NOT TESTED** |
| Provider/model selection | Set model matrix and web-agent model | Policy denial / unavailable model | **NOT TESTED** |
| Brownfield code generation | Preserve existing route and add new behavior | Protected route removal | **NOT TESTED** |
| Generated-file completeness | Inspect all generated files and line counts | Reproduce truncation or incomplete output | **NOT TESTED** |
| Correction | Correct an induced defect with exact route | Route mismatch / unavailable approved route | **NOT TESTED** |
| Correction rollback | Accept non-regressive correction | Regressive correction restores checkpoint | **NOT TESTED** |
| Branch/push | Create branch and push | Push denial / stale workspace | **NOT TESTED** |
| Pull request | Create PR with evidence | PR creation failure | **NOT TESTED** |
| CI success | Observe successful checks | Deliberately fail CI | **NOT TESTED** |
| CI diagnosis/fix | Diagnose and fix failing CI | Fix attempt itself regresses | **NOT TESTED** |
| CI recovery | Observe green checks after fix | Retry/idempotency behavior | **NOT TESTED** |
| Deployment | Deploy generated project | Deployment failure and recovery | **NOT TESTED** |
| Deployment tracking | Track state/progress/runtime URL | Timeout or stale status | **NOT TESTED** |
| Runtime smoke | Verify deployed endpoint/UI | Runtime failure after nominal deploy | **NOT TESTED** |

Do not report any of these journeys as passed without API responses, UI state, SSE/event log, command output, GitHub PR/check evidence, deployment state, or runtime response.

## Pasted-goal mandatory test checklist

The following is the exact checklist that remains useful when the broader E2E begins. The current backend suite covers many deterministic units, but that is not equivalent to running this checklist through dev.

### Route fencing

- approved Grok continuation cannot dispatch DeepSeek;
- initial routing is not invoked for exact approved continuation;
- fresh OpenRouter runs still use normal matrix/tier routing;
- exact override applies only to the approved target task;
- unavailable route stops without fallback;
- worker route, reviewer route, and attribution match;
- mismatch prevents reviewer and final attestation.

### Brownfield contracts

- Spring context path/class/method mappings canonicalize;
- multiple mappings, method arrays, slash normalization, and class prefixes work;
- preservation text resolves to an exact constraint;
- unchanged route passes;
- alias plus original route passes;
- replacement-only route fails;
- explicitly acknowledged breaking removal passes only with valid evidence;
- green self-authored tests cannot bypass removal;
- SCM publication is never invoked after compatibility failure.

### Correction

- mutation invalidates previous evidence;
- accepted correction regenerates required evidence;
- regression restores checkpoint;
- rejected correction cannot push;
- reviewer cannot attest another workspace version.

### Validation

- workflow YAML is selected and inspected;
- `build.yml` and `test.yml` are not excluded;
- generated build directories remain excluded;
- workflow-only requirements do not create endpoint requirements;
- adversarial LLM endpoint evidence cannot hard-fail;
- deterministic endpoint failure can hard-fail;
- missing build evidence is `UNAVAILABLE`;
- explicit build failure is `FAILED`;
- Maven wrapper is selected when present;
- task completion remains separate from final verification.

## Final deliverables checklist from the pasted goal

| Deliverable | Current state |
|---|---|
| Prioritized issue table with FIXED/PARTIAL/NOT REPRODUCED/BLOCKED | **Added to this appendix at requirement level; full persona/E2E issue table still pending.** |
| Root cause for every fixed defect | **Covered for PR #301 deployment and PR #302 boot repair; broader product issues still need per-issue RCA.** |
| Files and contracts changed | **Covered in the technical sections and merged PRs.** |
| Automated tests and exact results | **Covered for local/CI trust slice.** |
| Local verification summary | **Covered.** |
| Dev E2E journey matrix | **Matrix added; all rows accurately remain NOT TESTED.** |
| Remaining risks and release recommendation | **Covered; no production release recommendation is implied.** |
| Separate coherent commits and synchronized docs | **PR #301/#302 were separate; sibling docs repo remains outside scope.** |
| Push branches and raise PRs after verification | **Completed for PR #301/#302.** |

## Master remaining-work order

1. Run the authenticated Projects + AIDLC E2E on dev using the unrelated repository, recording evidence for every matrix row.
2. Reproduce the reported web-agent truncation scenario with the “simple web UI todo app” request across the supported model/provider combinations and inspect every generated file.
3. Exercise model matrix, web-agent model selection, OpenRouter keys, workspace lifecycle/history, semantic available/degraded modes, Compare History, Timeline, docs regeneration, and map refresh through the UI.
4. Execute correction acceptance and regression rollback through the deployed dev UI/API, including exact-route unavailable and policy-denied negatives.
5. Complete the individual and principal persona audits, then produce the prioritized P0/P1/P2 issue table with reproducible links/evidence.
6. Only after those results, decide whether another code batch is needed. Keep unrelated documentation-repo updates, CLI release work, production release, and VS Code work separate and explicitly authorized.
