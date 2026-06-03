# Handoff — agent-quality hardening + preview rebuild session (2026-06-01)

> **Resume-from-here doc.** Written for a laptop session WITH a working build (this session ran in a
> sandbox that blocks maven repos — `repo.spring.io` + central both 403 — so nothing Spring-wired
> could be compiled/verified here). Branch: **`claude/agent-competitive-review-F4boG`** → **PR #199**
> (base `dev`). Single ordered plan: [`docs/ROADMAP.md`](../ROADMAP.md). Full pending register:
> [`docs/pending.md`](../pending.md).

## 0. Do this first (the only hard blocker)
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # macOS
mvn verify -q          # compile + tests + spotless gate
mvn spotless:apply     # if the format gate complains, then re-verify
```
Everything on the branch is **unverified-compile**. The pure cores were validated standalone
(`StringEditMatcher` 13/13, `TaskWaveScheduler` 10/10, `clampFullRead` 8/8, `BootHealthEvaluator`
9/9), but the Spring-wired changes (LlmRouter, SearchFilesTool, SwebenchTaskService, the new tests)
need a real compile. **Once green → merge PR #199.** Watch for: unused-param warnings on the two
neutralised `SwebenchTaskService` methods (intentional); spotless line-length/import-order on new
files; `HttpClientErrorException.TooManyRequests` import in `LlmRouter`.

## 1. What's DONE + pushed (8+ commits on the branch)
- **Editing core:** `readFile` no-mid-truncation (60KB/2000-line head-first) + `StringEditMatcher`
  fuzzy cascade (`editFile`) + `replaceAll`/`occurrence`. Tests: `StringEditMatcherTest`,
  `ReadFileToolTest`, `EditFileToolTest`.
- **Router failover (closes audit M#68):** `LlmRouter.maybeFailover`, BYOK-safe, flag
  `router.failover.enabled` (V226, OFF). `isTransientError` 429-by-type. `LlmRouterTest` (8 cases).
- **Worker off-by-one:** 4 `@Disabled` tests re-enabled with corrected (+1) expectations (root cause
  = verify-before-done nudge, not `c72fa557`).
- **Oracle removal:** `SwebenchController` deleted + gold-`test_patch` injection neutralised.
- **ripgrep search:** `SearchFilesTool` rg fast path + Java fallback.
- **Parallel-exec core:** `harness/TaskWaveScheduler` + test (NOT wired to the executor).
- **verifyBoot core:** `deployment/preview/verify/BootHealthEvaluator` + test (the preview keystone's
  pure classifier; NOT wired yet — see §3).
- **Docs:** `docs/ROADMAP.md`, `docs/pending.md`, `docs/plans/{latency-reduction-phased,
  preview-live-environment-rebuild, preview-phase1-verifyboot-spec}.md`, doc-accuracy sweep.

## 2. The ordered plan (full detail in ROADMAP.md)
- **Wave 0:** `mvn verify` → merge #199; toggle `router.failover.enabled` ON.
- **Wave 1 (KEYSTONE):** `verifyBoot` — agent authors a run-recipe + boots/health-checks in-loop,
  feeds the BUILD score. This is preview Phase 1 + the Devin run-and-observe/verification-spine lever
  + de-LLM-judges the score, all at once.
- **Wave 2:** context-compaction flags ON (eval); skip redundant per-task builds; budget re-tune.
- **Wave 3:** preview rebuild — build-once/run-same + authenticated previews; buildpack default +
  **DELETE `DevDockerfileGenerator` (3,514 LOC)**; **DELETE the `vm` mode + `preview-engine` repo**.
- **Wave 4:** mobile = Expo/EAS + device cloud (never self-host simulators).
- **Wave 5:** mid-task re-planning; wire `TaskWaveScheduler`; RunOrchestrator next split; (opt) microVM.

## 3. The KEYSTONE — exactly where to resume the build
Spec: [`docs/plans/preview-phase1-verifyboot-spec.md`](../plans/preview-phase1-verifyboot-spec.md).
Pure core already built: `BootHealthEvaluator`. Build order (under a compiler):
1. `ReportInput.bootVerification` field + scorer wiring (null-guarded). 2. `PreviewVerifier` runner
(fail-open `Optional`, mirrors `WorkspaceTestRunner`). 3. `tool/VerifyBootTool` + `ToolRegistry` reg
+ flag `worker.verify-boot.enabled` (V227, OFF). 4. flag-gated prompt directive + `RunOrchestrator`
population. 5. Phase-0 buildpack adapter + deny-by-default egress + dedicated preview SA +
`CodeAbuseScanner` pre-build. 6. `mvn verify` + eval before any default-ON.
**Security is first-class** — see the spec §2/§5 + parent plan §6 (egress deny-by-default, fix
`CloudRunClient --allow-unauthenticated`, least-privilege SA, secret-free env, cleanroom dispatch,
fail-closed gates).

## 4. Pending items not done (nothing dropped — see pending.md for detail)
- 🔴 `mvn verify` → merge (§0). 🟡 Wave 2 flags/builds/budget (eval-gated). 🟡 verifyBoot wiring (§3)
  + the rest of the preview rebuild + the deletions. 🟡 mid-task re-planning; parallel-exec wiring.
  🟠 RunOrchestrator split (by-hand, local — automated extraction corrupted it twice). ⚪ Parked:
  grading-strategy weighting (verifyBoot de-risks it first), head-to-head report (your side), frontend
  tests / circuit breaker / JAVADOC.

## 5. Parked decisions / constraints to remember
- **Rebrand (pending F0):** rename ALL packages incl. `com.navneet.executespec` → drop the `navneet`
  segment; name TBD. Compiler-gated. **"Forge" rejected** — collides with Atlassian Forge (`@forge`
  npm scope likely taken); pick a collision-free mark + verify npm/VS Code-publisher availability.
- **Flags stay SUPER_ADMIN-toggleable** — founder said don't auto-flip defaults / no eval script.
- **Commits:** no Claude co-author / attribution footer (founder ask).
- **`verifyScore` honesty:** today 40–53% LLM-self-graded when tests don't resolve; the keystone (§3)
  is the fix — make BUILD/TESTS read executed reality.

## 6. How to verify the pure cores quickly (no Maven needed)
Each pure class was validated by copying it + a tiny `main` into `/tmp` and running `javac`+`java`
(see the commit messages). Re-runnable the same way if you want to re-confirm before wiring.
