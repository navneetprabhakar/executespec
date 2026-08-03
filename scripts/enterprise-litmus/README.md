# Enterprise litmus harness

A dependency-free, fail-closed evidence harness for the ExecuteSpec enterprise demo litmus. It does not run providers, merge pull requests, deploy, or create project-side resources automatically.

## Safety contract

- Only native DeepSeek (`deepseek-v4-pro-native`, `deepseek-v4-flash-native`) and Fireworks GLM 5.2 (`glm-5.2-fw`) are accepted.
- `init`, `status`, `advance`, `freeze`, and `invalidate` do not use the network.
- Preflight requires `--confirm-live=YES`.
- Fixture creation additionally requires `--confirm-github-mutation=YES`.
- Fixture deletion requires the exact `--confirm-delete=OWNER/REPO` value stored in the manifest.
- Paid hero runs, approvals, merges, CI-fix runs, and deployment tracking are intentionally evidence-only: perform them through an independently reviewed operator procedure, then record immutable IDs with `advance`.
- If any frozen deployment, fixture, project, route, request, approval, or client identity changes, invalidate and restart. Never edit a frozen manifest.

Secrets are accepted only through environment variables and are redacted from command/API responses before evidence persistence. The state file defaults outside the repository at `~/.local/state/executespec/enterprise-litmus/current/manifest.json` and is written mode 0600.
Child `git`, `gh`, and verifier processes receive a strict environment allow-list; ExecuteSpec,
provider, and GitHub token variables are never inherited by subprocesses or repository hooks.

## Offline checks

```bash
node --test scripts/enterprise-litmus/litmus.test.mjs
npm --prefix scripts/enterprise-litmus/fixture test
node --check scripts/enterprise-litmus/lib.mjs
node --check scripts/enterprise-litmus/litmus.mjs
```

## Initialize without network

```bash
EXECUTESPEC_LITMUS_PROVIDER=DEEPSEEK \\
EXECUTESPEC_LITMUS_MODEL=deepseek-v4-pro-native \\
node scripts/enterprise-litmus/litmus.mjs init --manifest /absolute/private/path/manifest.json
```

## State sequence

The manifest enforces preflight, fixture and two architecture baselines, freeze, three strictly sequential hero runs, merge and refreshed-SHA validation, continuation, deliberate CI failure and fix, optional deployment tracking, negative tests with zero unexpected deltas, and explicit cleanup. `advance` cannot skip a state.

Example evidence-only transition:

```bash
node scripts/enterprise-litmus/litmus.mjs advance \\
  --manifest /absolute/private/path/manifest.json \\
  --to PROJECT_LINKED \\
  --evidence '{"projectId":"...","repoId":"..."}' \\
  --confirm-external-operation-completed=YES
```

The fixture deliberately contains a target function after line 150 and an EOF sentinel. Verification must compare workspace and PR bytes exactly, require at least 151 lines, and require the sentinel. The fixture CI has exactly one job named `fixture-ci`.

## What remains operator-owned

The harness does not yet implement authenticated ExecuteSpec project creation/linking, architecture/doc generation, run submission/approval, PR merge, deliberate CI break/fix, deployment observation, or database-backed negative fixture provisioning. Those operations spend money or mutate external systems and require explicit reviewed adapters plus live confirmations. `advance` only records already-completed external work, requires a separate confirmation and non-empty immutable evidence, and does not perform that work. Public APIs cannot manufacture every corrupted negative state; unsupported cases must be recorded as `NOT_RUN`, never inferred as passing.
