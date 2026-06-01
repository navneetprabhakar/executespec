# Preview Phase 0 + 1 — verifyBoot implementation spec

> Created 2026-06-01 (RL-2026-05-066). Code-level spec for the keystone of
> [`preview-live-environment-rebuild.md`](preview-live-environment-rebuild.md) — the agent authoring
> a run-recipe and **booting + health-checking it in-loop**, feeding a deterministic signal into the
> score. Written so Phase 0/1 can be implemented in one pass once a build is available (the sandbox
> blocks maven repos, so the wired parts below are unverified-compile).
>
> **Already landed (pure, standalone-validated 9/9):** `deployment/preview/verify/BootHealthEvaluator`
> — the deterministic verdict classifier. The rest below wires signals into it.

## Mental model (mirror the smoketest seam)
The BUILD-mode test path is the proven template; verifyBoot is its sibling:

| Test path (exists) | verifyBoot path (to build) | Role |
|---|---|---|
| `smoketest/TestOutputParser` | `deployment/preview/verify/BootHealthEvaluator` ✅ | pure classifier |
| `smoketest/WorkspaceTestRunner` | `deployment/preview/verify/PreviewVerifier` | runner: gather signals, fail-open `Optional` |
| `tool/RunTestsTool` | `tool/VerifyBootTool` | worker tool exposing it in-loop |
| `ReportInput.testSummary` → TESTS slot | `ReportInput.bootVerification` → BUILD slot | scoring seam |

## The run-recipe contract (what the agent authors)
A task that produces a runnable app MUST leave, at the workspace/app root, ONE of:
1. an explicit `Dockerfile` (authoritative), or
2. a `Procfile` / `nixpacks.toml` (buildpack hint), or
3. nothing → the buildpack default (Nixpacks/CNB) auto-detects.

…plus a declared **port** + **health path** in `.agent-context/run-recipe.json`
(`{ "port": 3000, "healthPath": "/health", "startTimeoutSec": 60 }`). The worker prompt directive:
*"Before completeTask on a runnable app: ensure a run-recipe exists and call `verifyBoot`; if it
isn't HEALTHY, fix it."* No preview-time heuristic detection — the recipe is explicit or buildpack.

## 1. `deployment/preview/verify/PreviewVerifier` (the runner)
- `@Component`, `@RequiredArgsConstructor`. Deps: the buildpack/Docker image builder (Phase 0
  adapter), `ShellSandboxPolicy`, the cleanroom container stores (`SwebenchContainerStore` /
  `ProgramBenchContainerStore`) — mirror `WorkspaceTestRunner`'s constructor.
- `Optional<BootVerification> verify(Path workspace, RunRecipe recipe, String runId)`:
  1. build image (buildpack default or the agent's Dockerfile) → `buildSucceeded`.
  2. run the image in the **isolated, deny-by-default-egress** network (Phase 0), non-root,
     `cap_drop ALL`, `no-new-privileges`, read-only rootfs, resource caps, metadata blocked → `started`.
  3. poll `http://<container>:<port><healthPath>` until 2xx/3xx or `startTimeoutSec` → `healthHttpStatus`.
  4. `BootHealthEvaluator.evaluate(signals)` → return.
  5. **always** tear down the container.
- **Fail-open:** builder/runtime unavailable, sandbox-blocked, or any exception → `Optional.empty()`
  (scorer keeps today's behaviour; the headline can only get more honest, never break) — exactly the
  `WorkspaceTestRunner` contract.

## 2. `tool/VerifyBootTool` (the worker tool) 🔒
- `implements Tool`, name `verifyBoot`. Schema: optional `{ port?, healthPath?, startTimeoutSec? }`
  (defaults from `.agent-context/run-recipe.json`).
- **Security (non-negotiable, reuse existing primitives):**
  - `CodeAbuseScanner` over the workspace **before** build; block on hit (fail-closed).
  - dispatch through `ShellSandboxPolicy`; run in the cleanroom container (`--rm`, **`--network none`**
    for the boot unless the recipe explicitly declares an egress allowlist reviewed at the plan gate).
  - per-call **timeout** (cap = `startTimeoutSec`, hard ceiling 120s) + **output budget** (6k inline +
    disk spill, like `shell`/`buildProject`); no host network, no host FS beyond the mounted workspace.
  - **no platform secrets** in the boot env (reuse the `ComposeExecutor` env-var whitelist).
- Returns the `BootVerification` verdict + reason + truncated logs as an `Ok` envelope (so the worker
  can read failures and fix), or an `Err` only on sandbox/abuse block.
- Register in `ToolRegistry`; **gate behind `worker.verify-boot.enabled`** (default OFF) — when off,
  return an informative `{available:false}` Ok so the worker learns it's unusable (mirror the
  `runWorkspaceTests` gate pattern). Bind for the worker like other tools.

## 3. WorkerAgent integration
- Add the prompt directive (above) to `SequentialExecutor.SYSTEM_PROMPT_BASE` **behind the flag** (no
  prompt change when OFF — learn from RL-2026-05-061: an always-present new tool in the prompt can
  cause reasoning-only stalls on some providers, so keep it flag-gated + eval before default-ON).
- No change to the loop control; `verifyBoot` is just another tool call.

## 4. Scoring seam (de-LLM-judge the BUILD axis)
- Add `BootVerification bootVerification` to `report/model/ReportInput` (nullable = not measured).
- In `ValidationReportService`, when present, the BUILD slot reads the deterministic verdict:
  `HEALTHY` → full BUILD credit; `UNHEALTHY`/`TIMED_OUT`/`DID_NOT_START`/`BUILD_FAILED` → graded down
  with the verdict as `status_reason`. When null → today's behaviour (no regression). Thread it
  through both additive + multiplicative scorers like `testSummary` is.
- `RunOrchestrator.generateValidationReport` populates it from the last `verifyBoot` of the run (or a
  final `PreviewVerifier.verify` call) — same place `WorkspaceTestRunner` is invoked.

## 5. Phase 0 prerequisites (land first)
- **Buildpack adapter** (`Nixpacks` or CNB `pack`) behind `preview.buildpack.enabled` — image build
  used by `PreviewVerifier` and (later) the build-once preview.
- **Network policy:** per-preview isolated network, **deny-by-default egress**; build-time allowlist
  (package registries) separate from runtime (none by default).
- **Dedicated preview service account / project** (least privilege; no prod data) for any Cloud Run
  boot; metadata + internal CIDRs blocked at the network layer, not just `/etc/hosts`.
- **`CodeAbuseScanner` pre-build hook** shared by verifyBoot + the preview build.

## 6. Flag + migration
- `worker.verify-boot.enabled` (default OFF) + `preview.buildpack.enabled` (default OFF), seeded
  `V227`, SUPER_ADMIN-toggleable. Default-ON only after an eval (boot-pass-rate + verifiedScore
  correlation + the red-team checklist in the parent plan §6).

## 7. Build/verify order (for the implementing session, under a compiler)
1. `ReportInput.bootVerification` field + scorer wiring (smallest, behind null-guard). Test.
2. `PreviewVerifier` (fail-open). Test the fail-open contract like `WorkspaceTestRunnerTest`.
3. `VerifyBootTool` + `ToolRegistry` registration + flag gate. Test the gate + envelope shape.
4. Prompt directive (flag-gated) + `RunOrchestrator` population.
5. Phase 0 buildpack adapter + network policy + SA. Red-team checklist.
6. `mvn verify` + `spotless:apply`; eval (boot-pass-rate × 3 providers) before any default-ON.

## Exit criteria (Phase 1)
The agent reliably produces a bootable run-recipe; `verifyBoot` returns a deterministic verdict that
feeds the BUILD slot; no sandbox escape in the red-team run; default stays OFF until the eval passes.
