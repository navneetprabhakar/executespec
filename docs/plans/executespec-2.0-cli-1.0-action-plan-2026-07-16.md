# ExecuteSpec 2.0 + CLI 1.0 detailed action plan

**Date:** 2026-07-16
**Status:** READY TO START
**Scope:** ExecuteSpec 2.0 backend/Web and ExecuteSpec CLI 1.0 release readiness, stabilization, recovery, packaging, documentation, and release.

## Execution status

- **Phase 0:** IN PROGRESS.
- Fresh canonical backend checkout: `/Users/navneetprabhakar/Documents/antigravity/executespec`.
- Goal branch: `codex/executespec-2.0-release-goal` tracking `origin/main`.
- Current backend base: `0ae95edf563765a06fa233fccea547b9f3925e29` (tree `af2164cd72f5019647cfd72187538e143b2170b4`).
- The broken checkout was moved recoverably to `/Users/navneetprabhakar/.Trash/executespec-broken-20260716-154813`.
- The temporary suffixed clone was moved recoverably to `/Users/navneetprabhakar/.Trash/executespec-2.0-20260716-154813`.
- The authoritative action plan is the only untracked file in the fresh checkout.
- Remaining Phase 0 work: authenticate deployed-SHA evidence, finalize the release tuple manifest, and correct stale repository-authority statements.

This is the authoritative execution plan for the next goal. It supersedes the phase ordering in `pending-work-handoff-2026-07-16.md` where the two documents differ.

## Goal

Ship an evidence-backed ExecuteSpec 2.0 backend/Web release and ExecuteSpec CLI 1.0 release with:

- one traceable backend, CLI, and docs source tuple;
- authenticated adversarial live-dev evidence;
- validated native DeepSeek, OpenRouter, and Fireworks routing behavior;
- Web, CLI, and VS Code lifecycle/reconnect parity;
- practical fresh-JVM recovery without duplicate work or spend;
- independently verified generated-code, build, test, cost, and score evidence;
- reproducible npm and Homebrew packaging;
- current documentation, website material, recordings, and manuals;
- no unaccepted P0 or P1 release defects.

## Current known blockers

1. The unsuffixed backend checkout has broken Git worktree metadata. Its `.git` file points to a missing `executespec-2.0` worktree.
2. Remote backend branches and documented/deployed candidates are not yet one tuple:
   - `dev`: `1ef99fbe663fdc39cf9933c5fa6e8ca001de28fd`
   - `benchmark`: `1ef99fbe663fdc39cf9933c5fa6e8ca001de28fd`
   - `main`: `0ae95edf563765a06fa233fccea547b9f3925e29`
   - documented candidate branch: `c722ecbca190a04d815d63662dda3b27900d9e09`
   - last successful dev deployment: `80515876bfc8c3c0afe3774597b9cec3063b0c05`
3. Older documents disagree about whether suffixed or unsuffixed checkouts are canonical; the referenced suffixed backend and CLI paths are currently absent.
4. CLI source metadata has been aligned and pushed to `origin/main`; the current provenance baseline is `6bed095f1daa7cd8de813ba9575e172a5d7b1bae` (tree `7994f1cb5cbe007197c365194513f2d640c29908`):
   - `@executespec/core`: `1.0.1`
   - `@executespec/cli`: `1.0.1`
   - CLI dependency: `@executespec/core@1.0.1`
   - `package-lock.json` agrees with both workspace versions and the scoped dependency.
5. The aligned `1.0.1` scoped packages are not yet published. Registry state observed on 2026-07-16 is:
   - `@executespec/core`: `0.2.0`
   - `@executespec/cli`: `0.5.0`
   - legacy unscoped `executespec-core`: `0.2.1`
   - legacy unscoped `executespec`: `0.5.1`
6. The Homebrew formula is still pinned to version `0.5.0` and revision `dd4116c5f96929c0515e49c4233535174d2d4157`; it must be updated only after the final release SHA is fixed.
7. The CLI test-runner and refreshed TUI baseline changes are committed in the current baseline. The only remaining uncommitted CLI path is user-owned `ANALYSIS.md`; preserve it and exclude it from release staging unless Forge explicitly requests otherwise.
8. The docs checkout is clean and aligned with `origin/main` at `4911d699141075894e512aec582b9d94879e5ef1` (tree `2fc7848d41685d8d1e72dd40710d233dfb310dc0`).
9. Dev health returns HTTP 200, but authenticated deployed-SHA verification is still required.

No implementation or release evidence collected against an unidentified tuple counts as current release evidence.

---

## Phase 0 — Establish one authoritative release tuple

**Estimate:** 0.5–1 working day
**Release blocking:** Yes

### Actions

1. Freeze backend, CLI, and docs mutations while provenance is reconstructed.
2. Preserve the broken backend directory unchanged; do not rewrite its `.git` pointer in place.
3. Create a clean backend checkout in a temporary canonical path.
4. Compare the broken checkout against remote candidate trees using file hashes or `git diff --no-index` so untracked work cannot be silently lost.
5. Determine ancestry and content differences among `80515876`, `1ef99fbe`, `0ae95edf`, and `c722ecbc`.
6. Choose the intended backend candidate by content and release scope, not by branch name or timestamp.
7. Use `6bed095f1daa7cd8de813ba9575e172a5d7b1bae` as the current CLI provenance baseline. Preserve untracked `ANALYSIS.md` as user-owned and exclude it from release staging unless Forge explicitly changes that classification.
8. Preserve the aligned scoped-package policy (`@executespec/core@1.0.1` consumed by `@executespec/cli@1.0.1`) unless a subsequent release change requires a deliberate new version.
9. Confirm the docs candidate and identify stale checkout/SHA statements.
10. Authenticate to dev and record the deployed backend SHA without exposing credentials.
11. Create or refresh one release tuple manifest containing:

```text
backend_sha:
backend_tree:
frontend_api_baseline:
cli_sha:
cli_tree:
docs_sha:
deployed_dev_sha:
migration_head:
evidence_timestamp:
```

### Exit criteria

- One canonical checkout exists for each repository.
- No unexplained file from the broken backend or dirty CLI checkout is lost.
- Exact backend, CLI, docs, and deployed-dev SHAs are recorded.
- Release documents agree on repository authority and paths.
- All later evidence is bound to the selected tuple.

---

## Phase 1 — Re-seal the deterministic baseline

**Estimate:** 1–2 working days
**Release blocking:** Yes

### Backend and frontend actions

Run the full backend gate with Java 21:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn clean verify -q
```

Also run:

- Flyway migration inventory and identity tests;
- clean database installation through the current migration head;
- upgrade rehearsal from a representative existing schema;
- authenticated application boot;
- frontend tests, production build, format check, and lint;
- prompt-resource manifest validation;
- source NUL-byte and generated-resource checks;
- strict documentation-integrity validation.

Do not use Flyway repair, baselining shortcuts, history deletion, out-of-order application, or edits to applied migrations.

### CLI actions

From a clean candidate checkout:

```bash
npm ci
npm run build
npm test
npm run typecheck
npm run qa:tui
npm run qa:agentic
npm run qa:cli-security
scripts/prod_release.sh
```

The release gate must run without `--fast` or `--skip-tests`.

### Required evidence

- Exact command and candidate SHA.
- Runtime/toolchain versions.
- Start/end time and exit code.
- Test files, test totals, passes, failures, skips, and ignored cases.
- Migration head and database version.
- Tarball file lists and sizes.
- Explicit `NOT_RUN` or `NOT_MEASURED` labels for unverified platforms or behaviors.

### Exit criteria

- Backend, frontend, CLI, core, and VS Code deterministic gates pass.
- Clean-install and upgrade behavior are explicitly classified.
- Every failure has an owner and severity.
- Evidence manifests contain only current tuple hashes.

---

## Phase 2 — Authenticated live-dev adversarial assessment

**Estimate:** 2–4 working days
**Release blocking:** Yes

Use the approved SUPER_ADMIN dev credentials and native DeepSeek. Do not mix provider comparison into this phase.

### Persona scenarios

| Persona | Required scenario |
| --- | --- |
| Developer | Local bug fix, tests, diff inspection, and revert |
| Senior Developer | Multi-file refactor and failed-build recovery |
| Staff Engineer | Cross-repository contract change and dependency evidence |
| Engineering Manager | Plan approval, progress, spend, risk, and unblock flow |
| Product Manager | Requirement-to-plan review and acceptance evidence |
| SDET | Reproduce defect, generate tests, repair, and rerun |
| SDET Manager | Regression evidence and score-trust assessment |
| UX Researcher | First-run onboarding, disconnect, reconnect, and error recovery |

### Required failure injections

- Ambiguous or non-actionable prompt.
- Plan rejection and resubmission.
- Provider timeout.
- CLI disconnect during streaming.
- Web refresh during an active run.
- Local-hands disconnect while a tool is pending.
- Failed build and failed tests.
- Large tool output and large-file reads.
- Permission rejection.
- Duplicate replay/event delivery.
- Expired authentication.
- Insufficient credits or unavailable provider.

### Evidence per scenario

- Persona and exact prompt.
- Surface, viewport, timestamp, project, run ID, and session ID.
- Backend/CLI candidate tuple.
- Provider/model and routing/failover trace.
- Stable event/action IDs.
- Screenshot evidence for visible claims.
- Redacted raw event and terminal logs.
- Changed files and patch hash.
- Independent build and test output.
- Actual pass/fail/skip/ignored counts.
- Claimed verified score versus independently observed outcome.
- Cost, latency, token, cache, and reasoning evidence.
- Severity, reproduction steps, user impact, and recommended fix.

### Severity policy

- **P0:** data loss, trust-boundary bypass, duplicate mutation/spend, unrecoverable core workflow, or false successful result.
- **P1:** primary workflow broken, misleading evidence, reconnect failure, provider selection ignored, or critical cross-surface inconsistency.
- **P2:** significant usability/operability defect with a viable workaround.
- **P3:** minor polish or documentation defect.

### Exit criteria

- No open P0.
- No open P1 unless explicitly accepted by Forge with a documented limitation.
- Every visible claim has screenshot evidence.
- Every generated-code claim has independent code/build/test evidence.
- The Web score is never the sole correctness verdict.

---

## Phase 3 — Provider and model feature matrix

**Estimate:** 2–3 working days
**Release blocking:** Yes

### Required route order

1. Native DeepSeek.
2. OpenRouter fallback.
3. Fireworks fallback.

Gemini is excluded from the current comparison. Sarvam remains absent from production.

### Frozen feature cohort

Use the same prompts, evaluator, runtime, and acceptance criteria for each lane:

- two LOW tasks;
- two MEDIUM tasks;
- one tool-heavy task;
- one failure/recovery task;
- one project-grounded or large-file task.

This cohort validates product readiness; it does not support benchmark, leaderboard, or SOTA claims.

### Required measurements

- Resolved provider and model.
- Native tool-call validity.
- Time to first event and total latency.
- Same-provider retries and cross-provider fallback.
- Input, output, cache, and reasoning tokens.
- Provider-reported and internally calculated cost.
- Managed-AI credit debit.
- Build, test, review, and patch-correctness outcome.
- Recovery behavior.
- Empty-patch and false-completion rate.

### Failure cases

- Invalid native DeepSeek key.
- Native timeout or 5xx.
- OpenRouter unavailable.
- Fireworks unavailable.
- Missing BYOK key.
- Provider disallowed by organization policy.
- User-pinned route.
- Failover after partial tool history.
- Spend exhaustion during retry/failover.

### Exit criteria

- Healthy native DeepSeek is never silently replaced.
- Failed native DeepSeek follows native → OpenRouter → Fireworks.
- Tool history remains valid across fallback.
- No duplicate provider cost or managed-credit debit occurs.
- All spend is reconciled against the provider ledger.
- Model/capability overlays remain disabled without reproducible no-regression evidence.

---

## Phase 4 — Web, CLI, and VS Code parity

**Estimate:** 2–3 working days
**Release blocking:** Yes

### Required parity rows

- Authentication and logout.
- Account provider preference.
- Explicit per-turn provider/model.
- Session creation and continuation.
- Project-scoped chat.
- Run submission.
- Plan display and approval/rejection.
- Streaming.
- Disconnect/reconnect.
- Cursor replay and deduplication.
- Partial assistant-response recovery.
- Run cancellation.
- Blocked-continuation approval.
- Cost and credit visibility.
- Diffs and hunk navigation.
- Tests and validation evidence.
- Large-file range reads.
- Search paging and truncation markers.
- Local-hands tool requests.
- Permission decisions.
- Error, empty, and loading states.
- Session clear/reset.
- Final result and evidence export.

### Required cross-surface scenario

1. Start one server-owned session in Web.
2. Continue it in CLI.
3. Drop the stream mid-turn.
4. Reconnect from Web.
5. Verify the same action IDs and transcript.
6. Continue from CLI again.
7. Confirm no duplicate tool call, mutation, event, cost, or assistant turn.
8. Verify `/clear` consistently across clients.
9. Verify VS Code consumes the same durable state where supported.

### Exit criteria

- Every must-have row is `PASS` or explicitly `NOT_SUPPORTED`.
- Unsupported behavior is visible rather than silently degraded.
- Provider preference resolves identically across surfaces.
- Replay preserves stable event/action identity.
- No surface infers state from assistant prose.
- Any changed server contract lands in backend, CLI, and docs together.

---

## Phase 5 — Practical cross-JVM recovery

**Estimate:** 5–10 working days
**Release blocking:** Practical recovery is release-critical; literal Java-future migration is not the objective.

A Java `CompletableFuture` or synchronous HTTP request cannot be transferred between JVMs. The supported contract must instead persist intent and outcome, recover versioned workspace/checkpoint state, prevent duplicate mutation/spend, and expose uncertain provider outcomes.

### 5A — Workspace and checkpoint transfer

Implement:

- a versioned workspace/artifact manifest;
- immutable uploaded object generations;
- a database pointer updated through compare-and-set;
- run/task/checkpoint identity in every manifest;
- stale/mismatched generation rejection;
- persisted provider/model, continuation counters, event cursor, and cost evidence;
- single-winner recovery ownership;
- first-writer-wins terminal protection.

### 5B — Durable provider-call attempt state

Add a durable lifecycle:

```text
INTENT → DISPATCHED → COMPLETED
                    → FAILED
                    → UNKNOWN
```

Persist before dispatch:

- call ID;
- run/task/step;
- provider/model;
- request and tool-schema fingerprints;
- attempt number;
- provider idempotency key where supported;
- owner, lease, and epoch;
- managed-credit reservation reference;
- dispatch and expiry timestamps.

Rules:

- Never reissue an `UNKNOWN` call automatically unless the provider exposes a documented idempotency/replay contract.
- Otherwise stop as approval-required and expose the provider/model/cost uncertainty.
- A late response cannot overwrite a newer owner or terminal state.
- Provider cost and managed credits reconcile exactly once.
- Tool mutation occurs only after an accepted, persisted response.

### Chaos matrix

Kill or disconnect the active owner:

1. Before provider dispatch.
2. During the provider request.
3. After response receipt but before persistence.
4. After persistence but before tool dispatch.
5. During tool execution.
6. After mutation but before event publication.
7. During workspace upload.
8. While two recovery nodes race.

### Exit criteria

- Exactly one recovery owner wins.
- No duplicate file/tool mutation occurs.
- No duplicate provider or managed-credit charge occurs.
- A late response cannot overwrite terminal state.
- Workspace and checkpoint restore on a fresh JVM.
- Uncertain provider outcomes become explicit approval-required states.
- Documentation does not claim transferable in-flight futures.

---

## Phase 6 — Defect repair and regression control

**Estimate:** Variable; reserve 3–7 working days initially
**Release blocking:** Yes

### Repair order

1. P0 correctness, data, trust-boundary, replay, and duplicate-spend problems.
2. P1 core-loop failures.
3. Provider routing and cost errors.
4. Cross-surface semantic drift.
5. Evidence and score-honesty defects.
6. P2 UX and operability defects.
7. Large-class cleanup only where it directly reduces a demonstrated failure.

### Rules for every fix

- Add a seam-owned regression test in the same change.
- Keep APIs and state transitions explicit.
- Preserve constructor injection and package ownership.
- Update root/package/docs documentation in the same commit where required.
- Deploy the exact new candidate.
- Reproduce the original failure and rerun adjacent no-regression rows.
- Rebind the release tuple after every source change.

Do not begin a broad `RunOrchestrator`, shared frontend API, or UI-module rewrite during release stabilization.

### Exit criteria

- Every release-blocking issue is closed or explicitly accepted.
- Fixed scenarios pass on the exact deployed candidate.
- Adjacent regression rows remain green.
- Evidence and docs are rebound to the new tuple.

---

## Phase 7 — CLI 1.0.1 packaging and publication gate

**Estimate:** 1–2 working days
**Release blocking:** Yes

### Current version contract

The source and lockfile are already aligned at:

```text
@executespec/core: 1.0.1
@executespec/cli:  1.0.1
dependency:        @executespec/core@1.0.1
```

Do not perform another version bump merely to repeat alignment. A later source change must follow the chosen release/semver policy and update package metadata, lockfile, release docs, and formula coherently.

### Actions

1. Confirm that the release uses the scoped packages `@executespec/core` and `@executespec/cli`; treat legacy unscoped packages as compatibility/deprecation surfaces rather than silently publishing both naming schemes.
2. Preserve untracked `ANALYSIS.md` as user-owned and keep it out of release staging. The test-runner and TUI-baseline changes are already committed in the current CLI baseline.
3. Keep package metadata and lockfile aligned if the final candidate requires any further version change.
4. Update install commands and release documentation to the scoped CLI package where current docs still name the legacy unscoped package.
5. Run the complete release script.
6. Inspect both scoped-package tarballs.
7. Install them into an isolated npm prefix.
8. Smoke all three binaries and authenticated diagnostics.
9. Publish `@executespec/core@1.0.1` first through the guarded release script.
10. Verify `@executespec/core@1.0.1` from the public registry.
11. Publish `@executespec/cli@1.0.1` through the guarded release script.
12. Install public `@executespec/cli@1.0.1` into a fresh prefix and run an authenticated dev smoke.
13. Update the Homebrew formula to version `1.0.1` and the exact final release SHA; do not keep its current `0.5.0`/`dd4116c5...` pin.
14. Run strict formula audit and installed-binary smoke.

Required smoke commands include:

```bash
executespec --version
es --version
eslocal --version
executespec doctor
executespec auth status
```

### Exit criteria

- No direct `npm publish` bypass is used.
- The release script is the only publish path.
- `@executespec/cli@1.0.1` resolves `@executespec/core@1.0.1` from the public registry.
- Current documentation does not direct new installs to a stale unscoped package unless an explicit compatibility policy requires it.
- npm and Homebrew are independently verified.
- macOS status is evidence-backed.
- Linux, Linuxbrew, Windows, WSL, and Git Bash are explicitly marked verified, partial, or not-run.

---

## Phase 8 — Documentation, website, recordings, and manuals

**Estimate:** 2–3 working days
**Release blocking:** Documentation is blocking; website publication follows functional signoff.

### Documentation actions

Update and cross-check:

- root and package architecture documents;
- backend code map and API reference;
- document map and reason log;
- CLI status and release documentation;
- backend and frontend developer guides;
- customer Web manual;
- CLI and TUI manuals;
- recovery limitations;
- provider routing/fallback policy;
- AI-credit behavior;
- supported/unsupported platform matrix;
- known limitations and explicitly unverified features.

### Visual and recording actions

- Capture fresh authenticated Web screenshots from the final candidate.
- Record a current Web + CLI workflow.
- Include project management, run creation, plan approval, streaming, diff, tests, evidence, and reconnect.
- Redact credentials and sensitive repository data.
- Do not present synthetic material as live quality evidence.
- Render and visually inspect the final user-manual PDF.

### Exit criteria

- All screenshots and recordings bind to the final tuple.
- Historical evidence is clearly labeled historical.
- Strict docs-integrity validation passes.
- Website copy contains no unsupported quality or benchmark claims.
- Manuals describe cross-JVM and provider uncertainty honestly.

---

## Phase 9 — Promotion, deployment, and release

**Estimate:** 1 working day after all earlier gates pass
**Release blocking:** Final gate

### Sequence

1. Freeze the final backend, CLI, and docs tuple.
2. Run the final deterministic gates again.
3. Deploy the exact backend candidate to dev.
4. Authenticate and verify the deployed SHA.
5. Run the critical live smoke subset.
6. Confirm health, migration state, service state, and rollback procedure.
7. Prepare repository promotion PRs or exact-SHA alignment operations.
8. Forge performs ExecuteSpec PR merges; Codex must not merge them.
9. Align backend `dev`, `benchmark`, and `main` to the same SHA.
10. Verify remote branch SHA equality.
11. Verify required CI and deployment runs.
12. Publish npm packages.
13. Update and publish Homebrew separately.
14. Publish documentation, manuals, website updates, and recordings.
15. Seal the final evidence pack and operational handoff.

### Final release bar

- `dev = benchmark = main = deployed backend SHA`.
- Backend checkout provenance is healthy.
- No open P0.
- No unaccepted P1.
- Native DeepSeek and its fallback chain pass.
- Provider spend and managed credits reconcile with no duplicates.
- Web/CLI reconnect and replay parity pass.
- Fresh-JVM practical recovery passes without duplicate mutation or spend.
- Backend, frontend, CLI, core, and VS Code gates pass.
- Public CLI packages install and authenticate successfully.
- npm and Homebrew evidence are distinct and complete.
- Documentation and recordings match the released tuple.
- Unsupported behavior is explicitly documented.

---

## Explicit post-release backlog

The following work remains outside this goal:

- Real FastContext runtime/model evaluation.
- Django or equivalent large-repository retrieval cohort.
- Repeated prompt audit and baseline comparison.
- Subagent quality-uplift measurement.
- Graphify adoption review.
- Rust-versus-TypeScript measured investigation.
- Full relationship-aware repository index.
- Broad `RunOrchestrator`, frontend API, and large UI-module decomposition.
- Linuxbrew and native Windows local-hands certification.
- Native Terminal.app accessibility/keyboard capture if macOS permissions remain blocked.

## Estimated duration

- Provenance, baseline, live assessment, provider testing, and parity: **8–12 focused working days**, excluding defect repair.
- Practical cross-JVM workspace/provider-attempt recovery: **5–10 additional working days**.
- Initial repair allowance, packaging, documentation, and release: **6–13 working days**.
- Realistic total: **19–35 focused working days**, depending primarily on adversarial defects and recovery complexity.

## Goal completion definition

This goal is complete only when Phase 9 passes. Producing plans, deterministic tests, screenshots, or partial evidence does not by itself complete the goal. Any explicitly deferred release-critical row must be accepted by Forge in writing and recorded as a known limitation before release.
