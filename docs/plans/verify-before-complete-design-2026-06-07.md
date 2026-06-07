# Generic Verify-Before-Complete — Design Plan

> **Status:** DESIGN (no code yet). Authored 2026-06-07.
> **Branch:** `docs/handoff-cleanup-reprioritize` (held — no commits/pushes pending user coordination).
> **Motivation source:** the DeepSeek Terminal-Bench base20 eval (12/20, flat vs the prior 12/20 best) + the 8-failure analysis below.
> **Mandate:** provider-agnostic (Anthropic / OpenAI / Gemini / DeepSeek), product-wide (not benchmark-specific) — "improve the system, not the test."

---

## 1. Why this exists — the evidence

The 2026-06-07 DeepSeek TB-20 run with the prompt-only "TB lever" (anti-over-engineering + symptom-anchored fix-verify) scored **12/20 — flat** vs the prior best. Reading all 8 failure transcripts (`/tmp/es-tb-jobs/es-deepseek-base20-tblever/<task>/verifier/test-stdout.txt`) produced this taxonomy:

| Task | Tools | Outcome | Failure class |
| --- | --- | --- | --- |
| `filter-js-from-html` | 50 | COMPLETE | **Over-engineering** — filter modified 5/12 *clean* HTML files that should pass through byte-identical |
| `sanitize-git-repo` | 44 | COMPLETE | **Symptom-sweep miss** — scrubbed 4/5 secrets, missed `HUGGINGFACE_TOKEN_2` in `~/.bashrc` + `~/.cache/...` |
| `build-cython-ext` | 52 | COMPLETE | **Verification gap** — 10/11 tests pass, one `np.int` (removed numpy alias) left |
| `write-compressor` | 30 | COMPLETE | **Deliverable never produced** — `/app/data.comp` doesn't exist, yet the agent COMPLETED |
| `chess-best-move` | 50 | COMPLETE | Wrong answer — found `g2h3`, correct was `g2g4` |
| `gcode-to-text` | 75 | COMPLETE | Wrong answer — output `"Embossed text"` instead of decoding the flag |
| `feal-linear-cryptanalysis` | 25 | COMPLETE | Wrong answer — hard cryptanalysis, wrong key |
| `largest-eigenval` | 36 | WALL_CLOCK | Performance — 18 correct, 9 `test_speedup` too slow |

**Key insight:** two of these (`filter-js-from-html`, `sanitize-git-repo`) are *exactly* the prompt lever's target failure modes, and the prompt lever fixed neither. Prompt text alone does not move these behaviors on a schema-sensitive model. The lesson generalizes: **steering ≠ enforcement.**

The failures collapse into one generic gap, not eight special cases:

> **The worker asserts "done" (`completeTask`) without the system enforcing that the deliverable actually works.**

- `write-compressor` — wrote a compressor, never *ran* it → no output. Asserted done.
- `build-cython-ext` — never ran the *full* test suite → didn't see the break. Asserted done.
- `sanitize-git-repo` — scrubbed the obvious secrets, never re-scanned → missed one. Asserted done.

This is a **product** gap, not a benchmark gap: every BUILD-mode customer run today can complete with a deliverable that compiles but doesn't run. The benchmark just made it visible.

---

## 2. Current state — grounded gate inventory

Reconnaissance over the real code (not docs) found the verify machinery already exists, but it is **scattered, mode-specific, opt-in, and post-hoc** — and the one time it was made mandatory in-loop, it was a *model tool*, which is what broke DeepSeek.

| Gate | Where (file) | Default | Shape |
| --- | --- | --- | --- |
| Build smoke → 1 retry | `harness/SequentialExecutor.java` (~L788–927) | on (BUILD) | post-hoc; on fail spawns a **separate** retry worker with the build error injected (~L862) |
| Test-exec gate `build.test-exec-gate.enabled` (V209) | `SequentialExecutor.runSmokeTest` (~L1099–1127) | **OFF** (reverted, RL-2026-05-061) | post-hoc; flips smoke→FAILED on test failure, reuses the retry path |
| `runWorkspaceTests` tool `worker.build-tdd.enabled` (V217) | model schema, `tool/WorkspaceRunTestsTool.java` | **OFF** (reverted — caused DeepSeek reasoning-only stalls) | **model-discretion tool** |
| Test gate `requireTestVerification` | `engine/WorkerAgent.java` (~L690–727) | PATCH-only | in-loop **soft** refusal (logs, `continue`) |
| Build gate `requireBuildVerification` | `engine/WorkerAgent.java` (~L728–769) | reviewer-retry-only | in-loop **soft** refusal |
| TB deliverable / critic / verify gates | `engine/WorkerAgent.java` (~L770–873), `terminalTaskMode` | TB-only (env `ES_TB_*`) | in-loop, **one-shot** (each fires ≤ once, fail-open) |
| Reviewer fix-build third pass `reviewer.third-pass.enabled` (V202) | `reviewer/ReviewerAgent.java` (~L313–359) | on | post-hoc, **separate** reviewer-stage worker |
| `verifyBoot` `worker.verify-boot.enabled` (V227) | `tool/VerifyBootTool.java`, `preview/PreviewBootEngine.java` | **OFF + inert** | interface-only, **no engine bean**, fail-open to empty |

### Two hard facts that define the gap
1. **No mechanism ever *executes* the deliverable.** The system compiles source and runs tests, but nothing runs `python compress.py input.txt` to confirm `data.comp` appears, or hits an endpoint to confirm it responds. That is the entire `write-compressor` class. The `verifyBoot` seam was meant to start this for web apps, but it is an unimplemented interface (no bean), flag-OFF, fail-open.
2. **The only "mandatory in-loop verify" attempt was a model tool — and it stalled DeepSeek** (RL-2026-05-061: `runWorkspaceTests` in the schema → reasoning-only no-edit stalls; astropy-14182 went 0 B no-edit ON → 1079 B edit OFF). The architectural lesson: **verification must be a server gate, not a tool the model chooses to call.**

### A blessed precedent
RL-2026-06 already **generalized the TB-specific critic + deliverable gates into workspace-agnostic capabilities in the shared `engine/WorkerAgent`** (V231 `worker.critic-pass.enabled`). "Fold a mode-specific gate into a generic shared capability" is therefore an established direction in this codebase, not a new pattern being invented here.

---

## 3. The thesis

> Unify the eight scattered gates into **one workspace-agnostic, server-enforced `VerifyBeforeComplete` capability** in `engine/WorkerAgent` that resolves *what verification is possible for the current execution context* into a `VerificationPlan`, gates `completeTask` on it, and loops failures back within budget — identically across BUILD / PATCH / TB and all four providers.

The keystone — and the reason it is provider-agnostic — is that it is a **server gate, not a model tool**: it cannot trigger the schema-sensitivity stall that killed the last attempt, and the model literally cannot "forget" to verify. The model never sees a new tool; it just sees a refusal-with-evidence if it claims done prematurely (the same mechanism the PATCH `requireTestVerification` gate already uses at `WorkerAgent.java:690–727`, generalized and made default for BUILD/TB).

### Core abstractions

```
VerificationPlan {
  build:          Optional<Command>   // e.g. mvn -q compile  /  pip install -e .
  test:           Optional<Command>   // resolver-detected: pytest / mvn test / npm test / cargo test / go test
  runDeliverable: Optional<RunСheck>  // NEW (Tier 2): execute the artifact, assert exit==0 / declared output exists
}

VerificationContext  // resolves a plan + EXECUTES commands in the right place:
  - host workspace            (BUILD / PATCH local)        -> ShellTool/BuildProjectTool host path
  - cleanroom container       (SWE-bench / ProgramBench)   -> docker exec (ProgramBenchContainerStore)
  - remote TB container       (Terminal-Bench)             -> RemoteToolDispatcher
```

The resolver reuses what already exists: `smoketest/WorkspaceTestRunner` (pytest/mvn/gradle/npm/cargo/go detection), `tool/BuildProjectTool`, and the container/remote routing already consulted by `ShellTool`/`BuildProjectTool`. The plan is *resolved once per task* and *executed server-side* at the `completeTask` boundary.

### The gate hook
At `WorkerAgent.dispatchOne`'s `completeTask` handling (`WorkerAgent.java:~683–910`), where the opt-in `requireTestVerification` / `requireBuildVerification` soft gates already live: when `VerifyBeforeComplete` is active, on a `completeTask` call the server runs the resolved `VerificationPlan`. If it fails, the completion is **refused** and the concrete failure (build error / failing test names / "deliverable produced no output") is fed back as the next `UserMessage` — reusing the existing refusal-then-continue machinery and the existing budget/stagnation caps so it can never loop forever. If it passes (or no verification resolves — fail-open), completion is accepted.

---

## 4. Tiers

### Tier 1 — unify + server-enforce (foundation, no new execution)
Collapse the existing build/test/TB gates behind one `VerifyBeforeComplete` gate driven by a resolved `VerificationPlan`, wired first to verification that **already exists** (build + the project's own tests via `WorkspaceTestRunner`). This is *consolidation*: the per-mode special-casing (`requireTestVerification`, `requireBuildVerification`, `terminalTaskMode` deliverable gate, the post-hoc smoke retry) becomes one code path parameterized by the resolved plan.
- **Flag:** `worker.verify-before-complete.enabled` (new Flyway seed, default OFF).
- **Fixes:** `build-cython-ext` (where the worker's tests reach the break), the BUILD/PATCH product value (verified-passing tests before "done").
- **Risk:** the owed RL-2026-05-061 A/B — must show no regression on each provider's passing set before any default flip.

### Tier 2 — the new primitive: deliverable execution
Add `runDeliverable` to the plan: after a clean build, execute the produced artifact on a trivial happy-path invocation and assert it exits 0 / produces its declared output artifact. This is the `verifyBoot` seam **generalized beyond web apps** (CLI tools, scripts, binaries — not just HTTP servers). Resolution of "what to run" comes from the task spec / the worker's own mandatory happy-path test (the tests-are-mandatory directive already requires one; this makes it *count*).
- **Flag:** `worker.verify-deliverable.enabled` (default OFF).
- **Fixes:** `write-compressor` (no output → reject → fix) and the general "compiles but doesn't run" class — the highest-value *product* capability ("the code actually works before we ship a PR").
- **Risk:** needs a safe, bounded execution sandbox (reuse `ShellSandboxPolicy` + cleanroom container); must fail-open if the run harness itself errors (never block a good run on infra).

### Tier 3 — soft, honest: minimal-diff / scope-creep (the over-engineering class)
`filter-js-from-html` (over-modifying clean inputs) is **not server-checkable without an oracle**. Honest exception: it stays a prompt principle (the C5 "build only what the task asks" directive already added to `SYSTEM_PROMPT_BASE`) plus an optional **reviewer** check — "did the diff touch files/regions unrelated to the task's stated scope?" Lower confidence; no gate pretends to solve it.

---

## 5. What it fixes (failure → tier)

| Failure | Class | Addressed by | Confidence |
| --- | --- | --- | --- |
| `write-compressor` | deliverable not produced | Tier 2 (run-deliverable) | high |
| `build-cython-ext` | verification gap | Tier 1 (run tests) | medium (hidden-test coverage dependent) |
| `sanitize-git-repo` | symptom-sweep miss | Tier 2 + completeness re-scan hook | medium |
| `filter-js-from-html` | over-engineering | Tier 3 (prompt + reviewer) | low — honest exception |
| `chess` / `gcode` / `feal` | wrong answer | — (DeepSeek-Flash capability ceiling) | out of scope |
| `largest-eigenval` | performance + wall-clock | — (perf engineering + time) | out of scope |

**Honest accounting:** of the 8, this design plausibly converts **2–3** (write-compressor, build-cython-ext, maybe sanitize). The other 4 are model-capability / performance ceilings that no gate fixes. The *product* value is broader than the benchmark delta: every BUILD/PATCH run gains a server-verified definition of done.

---

## 6. Provider-agnostic guarantees

1. **Server gate, never a model tool** → no schema-sensitivity stall (the RL-2026-05-061 failure mode is structurally excluded).
2. **One capability across BUILD / PATCH / TB** → the same `VerifyBeforeComplete` runs in every mode, differing only in the resolved `VerificationContext` (host / container / remote). No per-provider, no per-mode branches in the policy itself.
3. **A/B per provider** before any default flip — the matrix below.

---

## 7. Risks + the owed A/B

- **The RL-2026-05-061 owed gate.** V209/V217 were reverted *together*; the documented stall was the *tool in the schema*, not the server gate. **De-risking experiment (do this first):** A/B the server-side test-exec gate **alone** (`build.test-exec-gate` ON, `runWorkspaceTests` tool OFF) on the TB-20 + a SWE-bench Lite slice × {DeepSeek, Anthropic, GPT, Gemini}. If the server gate alone is clean on DeepSeek, the "server-not-tool" thesis holds and Tiers 1–2 are safe to build.
- **Cost + latency.** Each verification adds a build/test/run cycle per task. Bound it: cache the resolved plan, skip re-verify if no `writeFile`/`editFile` since the last green verify, cap retries via the existing budget/stagnation machinery.
- **Fail-open discipline (non-negotiable).** If verification *infrastructure* fails (no runner resolves, container won't start, runner output unparseable), the gate MUST fail-open and accept completion — verification is a *tightening* signal, never a new way to fail a good run. Mirrors the existing `WorkspaceTestRunner` damped-fallback discipline.
- **TB path nuance.** TB runs are remote (no `SequentialExecutor` smoke). The generic gate reaches TB only if `VerificationContext` routes through `RemoteToolDispatcher`. This is the unification's payoff — but it means the TB-specific `ES_TB_*` gates should be *migrated onto* the generic capability, not left parallel.

---

## 8. Flags + migrations (proposed)

| Flag | Tier | Default | Seed |
| --- | --- | --- | --- |
| `worker.verify-before-complete.enabled` | 1 | OFF | V235 (next free; confirm head before authoring) |
| `worker.verify-deliverable.enabled` | 2 | OFF | V236 |
| (reuse) `build.test-exec-gate.enabled` (V209) | A/B | OFF | — flip for the narrow experiment only |

All default OFF → production byte-identical until eval data justifies a flip. Per the Flyway rule: confirm the migration head (`ls V*.sql`) before authoring; never reuse a number; cross-link each from `tool/CLAUDE.md` + `engine/CLAUDE.md` + the root migration log.

---

## 9. Rollout sequence

1. **Narrow A/B** — `build.test-exec-gate` alone, TB-20 + SWE-bench slice × 4 providers. Decision gate: server-gate-safe-on-DeepSeek? (yes → proceed; no → rethink the enforcement seam).
2. **Tier 1** — build `VerificationPlan` + `VerificationContext` + the `VerifyBeforeComplete` hook; wire to build+test; migrate the opt-in gates onto it behind the flag. A/B no-regression per provider.
3. **Tier 2** — add `runDeliverable`; A/B; this is where `write-compressor` flips.
4. **Default-on per mode** only after the A/B shows ≥0 net across every provider's passing set (the RL-2026-05-061 discipline).
5. **Migrate** the TB `ES_TB_*` gates + the `verifyBoot` seam onto the generic capability; delete the parallel paths.

---

## 10. Open questions (for the user)

1. **Scope of Tier 2's "expected output"** — for `runDeliverable`, where does "what should it produce" come from? Options: (a) the worker's mandatory happy-path test (cheapest, already required), (b) a structured deliverable contract the planner emits, (c) the spec parsed at plan time. (a) is the pragmatic start.
2. **Default-on appetite** — is the end-goal default-ON in BUILD mode for customers (verified-working deliverables as a product guarantee), or keep it opt-in / ENTERPRISE-gated?
3. **TB migration timing** — fold the `ES_TB_*` gates onto the generic capability now (one code path) or after Tier 1/2 prove out on the product path?
4. **Cost ceiling** — acceptable added latency/cost per task for the verify cycle (affects how aggressively we re-verify)?

---

## Appendix — file:line anchors (from 2026-06-07 recon)

- completeTask gates: `engine/WorkerAgent.java:690–910` (test ~690–727, build ~728–769, deliverable/critic/verify nudges ~770–873, accept ~874–910)
- per-task smoke + retry: `harness/SequentialExecutor.java:788–927`; post-compile test-exec gate ~1099–1127
- reviewer fix-build: `reviewer/ReviewerAgent.java:313–359`
- verify-boot seam (inert): `tool/VerifyBootTool.java`, `preview/PreviewBootEngine.java` (interface, no bean), `preview/PreviewVerifier.java` (fail-open)
- test runner: `smoketest/WorkspaceTestRunner.java`
- V231 precedent (TB gates → shared capability): `worker.critic-pass.enabled`

---

## 11. Tier 1 — build-ready integration spec (grounded 2026-06-07)

> **Discovery that shrinks the work:** the seams already exist. `engine/VerifyMode` (NONE/SOFT_NUDGE/HARD_GATE) is the verify lever as *data*; `WorkerAgent.dispatchToolDirect(ctx, tool, args)` (L1636) is the workspace-agnostic executor (remote→`dispatchRemote`, local→`tool.execute`, returns an `{ok,content}` envelope); `WorkerAgent` already runs **five** one-shot `completeTask` stages (L683–911); and `missingDeliverables` (L1983) already does a *server-side* check via `dispatchToolDirect` + envelope-parse + **fail-open**. VBC Tier 1 is therefore a **4th stage mirroring the deliverable gate**, not a new subsystem.

### The crucial distinction
The existing test/build gates (`requireTestVerification` L690, `requireBuildVerification` L735) are **model-dependent soft gates** — they check whether the *model* called `runTests`/`buildProject` recently. In BUILD mode both are OFF by default, so a worker that never builds is never gated. VBC **runs build+test itself, server-side**, regardless of what the model did. That is the new capability.

### Change set (mechanical)

**1. `engine/WorkerTaskRequest.java`** — add one field (mirror `criticPass`):
```java
Boolean verifyBeforeComplete   // server-run build+test gate before completeTask; null/false = inert
```

**2. `engine/WorkerAgent.java`**
- Derive activation near L300–356: `final boolean vbcOn = Boolean.TRUE.equals(req.verifyBeforeComplete());`
- Add a bounded counter `int vbcRefusals = 0;` + constant `VBC_GATE_MAX_REFUSALS = 3;` (≥ deliverable's 2 — build fixes can take an extra round).
- **New stage** inserted after the deliverable gate (L807), before the critic pass (L819) — deterministic build/test before paying for the LLM critic:
```java
// #VBC verify-before-complete (RL-2026-06-…, worker.verify-before-complete.enabled, V235).
// SERVER-RUNS build+test (vs the soft gates above that only check if the MODEL did).
// Bounded + fail-open: no resolvable command / infra error => accept (never false-block).
if (vbcOn && vbcRefusals < VBC_GATE_MAX_REFUSALS) {
  String failure = verifyBeforeCompleteFailure(req.context()); // null = pass or fail-open
  if (failure != null) {
    vbcRefusals++;
    String refused = errEnvelope("VERIFY_FAILED", verifyFailedMessage(failure));
    toolResponses.add(new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), refused));
    toolLog.add(tc.name() + " → REFUSED (verify-before-complete: build/test red)");
    log.info("WorkerAgent completeTask VBC-GATED run={} task={} iter={}",
        req.context().runId(), req.context().taskId(), iter);
    continue;
  }
}
```
- Suppress the soft verify-nudge (L848) when `vbcOn` (the hard gate supersedes it — same way `criticOn` already suppresses it in the L850 condition: add `&& !vbcOn`).
- **New helper** mirroring `missingDeliverables` (L1983) — server-runs build, then tests, parses the envelope, fail-open:
```java
/** Server-runs build+test via dispatchToolDirect. Returns the failure detail, or null on pass/fail-open. */
private String verifyBeforeCompleteFailure(ExecutionContext ctx) {
  try {
    // BUILD: resolver-detected build (reuse the task's buildCommand if present; else skip → fail-open).
    String build = dispatchToolDirect(ctx, "buildProject", "{}");
    JsonNode b = objectMapper.readTree(build);
    if (!b.path("ok").asBoolean(true)) return "build: " + firstLine(b.path("content").asText(""));
    // TEST: reuse the resolver-detected runner (WorkspaceTestRunner via runWorkspaceTests tool).
    String test = dispatchToolDirect(ctx, "runWorkspaceTests", "{}");
    JsonNode t = objectMapper.readTree(test);
    if (t.path("ok").asBoolean(true) && t.path("content").asText("").contains("\"available\":false"))
      return null;                       // no runner resolved → fail-open (don't block)
    if (!t.path("ok").asBoolean(true)) return "tests: " + firstLine(t.path("content").asText(""));
    return null;                          // green
  } catch (Exception e) {
    log.debug("VBC gate check failed (fail-open) run={}: {}", ctx.runId(), e.getMessage());
    return null;                          // fail-open
  }
}
```

**3. Activation wiring** — `harness/SequentialExecutor` (BUILD worker request) + `agent/PatchAgent` (PATCH): set `.verifyBeforeComplete(featureFlagService.isEnabled("worker.verify-before-complete.enabled"))`. Both already inject `FeatureFlagService`.

**4. Migration** — `V235__seed_worker_verify_before_complete_flag.sql`, default OFF. ⚠️ **Number is contingent** on how W3.1's `V234` reconciles — finalize at commit (`ls V*.sql`); if W3.1/V234 drops, this becomes V234.

**5. Tests** — `WorkerAgentVerifyBeforeCompleteTest`: (a) red server-run build → `completeTask` REFUSED; (b) green build + red tests → REFUSED; (c) green build + green tests → ACCEPTED; (d) `runWorkspaceTests`→`available:false` (no runner) → fail-open ACCEPT; (e) dispatch throws → fail-open ACCEPT; (f) `VBC_GATE_MAX_REFUSALS` exceeded → YIELD (accept). Mock `dispatchToolDirect` envelopes.

### Why it's safe + provider-agnostic
- **Server gate, no model-visible tool/schema change** → structurally immune to the RL-2026-05-061 schema-stall (the failure mode that reverted the last attempt).
- **Default OFF** (flag) → production byte-identical until A/B justifies a flip.
- **Fail-open + bounded** → an unresolvable runner / infra blip can never convert a good run into a false FAIL.
- **One code path across BUILD/PATCH/TB** (it runs via `dispatchToolDirect`, which already handles local vs remote).

### Tier 2 hook
Extend `missingDeliverables` (L1983) from "path exists" to "run-and-check": after existence, dispatch the deliverable's run command and assert exit 0 / declared-artifact present. Same envelope-parse + fail-open. This is where `write-compressor` flips. Open question §10.1 (source of "expected output") gates the exact shape.

### Open items before coding
- **Migration number** — resolve W3.1/V234 first (or accept the contingency note).
- **Build command resolution** — `buildProject` with `{}` uses the task's `buildCommand`; confirm BUILD-mode tasks carry one and PATCH/TB resolve sanely (else the build step fail-opens, which is acceptable for Tier 1).
- **Branch safety** — the WorkerAgent/WorkerTaskRequest/SequentialExecutor edits touch the files that collided with the CLI session via `git add -A` sweeps. Apply only once the sibling session is confirmed closed.

---

## 12. Tier 1 — IMPLEMENTATION STATUS (landed 2026-06-07, flag default OFF)

**Done + verified green.** The server-enforced verify-before-complete **test gate** is built behind `worker.verify-before-complete.enabled` (V235, default OFF). Production byte-identical until the flag flips.

### Final shape (and where it deviates from the §11 spec)
- **Test-gate only** (not build+test). VBC server-runs the project's **test suite** via `smoketest/WorkspaceTestRunner` **directly** (not the flag-gated `runWorkspaceTests` tool → decoupled from V217 `worker.build-tdd.enabled`; no model-visible schema change → immune to the RL-2026-05-061 stall). The *build* step was deferred: build-red is already partly caught by the existing post-loop smoke + reviewer fix-build, and `buildProject`'s no-build-command semantics are murky (a clean fail-open is harder). The genuinely-new value is the in-loop **test** gate.
- **Activation:** `PatchAgent` (PATCH — SWE-bench + autofix) every run; `SequentialExecutor` (BUILD) **final task only** (gated on `isFinalTask`, mirroring the smoke gate) so an intermediate task isn't blocked by a later task's not-yet-written tests. Remote/TB runs **fail-open** (the host runner can't reach a container — generic remote VBC is a follow-up; TB keeps its own `ES_TB_*` gates).
- **Mechanism:** a new `completeTask` stage in `engine/WorkerAgent` (after the deliverable gate, before the LLM critic), bounded to `VBC_GATE_MAX_REFUSALS=3` then YIELD; **fail-open** on unwired runner / remote / no-runner-resolves / zero-tests / any exception. Refusal feeds the failing-count detail back so the worker fixes before completing.

### Files
- `engine/WorkerTaskRequest.java` — `+ Boolean verifyBeforeComplete`.
- `engine/WorkerAgent.java` — `+ WorkspaceTestRunner` field (trailing, null-guarded), `vbcOn` activation, `vbcRefusals` counter + `VBC_GATE_MAX_REFUSALS`, the new stage, soft-nudge suppression (`&& !vbcOn`), `verifyBeforeCompleteFailure()` + `verifyBeforeCompleteMessage()`.
- `agent/PatchAgent.java` + `harness/SequentialExecutor.java` — activation from the flag.
- `db/migration/V235__seed_worker_verify_before_complete_flag.sql` — seed OFF.
- `WorkerAgentVerifyBeforeCompleteTest.java` (8 tests) + the 9 existing `new WorkerAgent(...)` call sites (+1 trailing null).

### Verification
`mvn compile` + `test-compile` clean; **71 WorkerAgent tests pass** (8 new VBC + the 9 modified files + the rest); my 3 main files **spotless-clean**. (Full `mvn verify` not run — the shared tree carries the sibling session's unrelated uncommitted work + format violations; verified my slice in isolation.)

### NOT done (follow-ups)
- **Build-step gating** (run `buildProject` server-side too, once its no-command semantics are pinned).
- **Remote/TB VBC** (route the test run through `dispatchToolDirect`/`RemoteToolDispatcher` so it works in a container).
- **Tier 2** — deliverable execution (extend `missingDeliverables` from "path exists" to "run-and-check"). The `write-compressor` fix.
- **Doc-refresh to shared docs** (root `CLAUDE.md` migration list/head → V235, `engine/CLAUDE.md` stage table, `docs/CODEMAP.md`, `docs/reason-log/2026-05.md`) — **held for commit time** (editing shared docs now risks colliding with the sibling's branch reconciliation).
- **The owed A/B** (RL-2026-05-061 cross-provider no-regression gate) before any default flip.

### Held
- **Git commit + push** — pending the user's branch reconciliation (shared tree has the sibling's uncommitted run-local/idempotency work intermixed). All VBC files backed up to `/tmp/vbc-tier1-backup/` (`vbc-tracked.patch` + V235 + the new test).
