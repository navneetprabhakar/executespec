# Enterprise litmus harness

A dependency-free, fail-closed evidence harness for the ExecuteSpec enterprise-demo litmus. It does
not run providers, merge pull requests, deploy, or create project-side resources automatically.

## Safety contract

- Only native DeepSeek (`deepseek-v4-pro-native`, `deepseek-v4-flash-native`) and Fireworks GLM 5.2
  (`glm-5.2-fw`) are accepted.
- `init`, `status`, `advance`, `freeze`, and `invalidate` do not use the network.
- Preflight requires `--confirm-live=YES`; fixture creation additionally requires
  `--confirm-github-mutation=YES`.
- Fixture deletion requires the exact `--confirm-delete=OWNER/REPO` value stored in the manifest.
- Paid hero runs, approvals, merges, CI fixes, and deployment tracking are evidence-only. Perform
  them through an independently reviewed operator procedure and record typed proof with `advance`.
- `advance` validates a state-specific, closed evidence contract and copies proof into authoritative
  manifest fields. An event entry alone never satisfies a gate.
- Prefer `--evidence-file PATH`. It must be a regular mode-0600 file. Inline `--evidence` is limited
  to 16 KiB, must be non-secret, and is intended only for short local proof.
- If any frozen deployment, fixture, project, route, request, approval, or client identity changes,
  invalidate and restart. Never edit a frozen manifest.
- Schema v2 manifests are deliberately not auto-migrated because v3 adds mandatory sandbox and CLI
  evidence that cannot be reconstructed safely. Preserve and archive the v2 file, use the v2 harness
  revision to clean up every resource recorded in it, then initialize a new v3 campaign. Never
  replace a discovered v2 file in place.

Secrets are accepted only through environment variables and are redacted before evidence
persistence. The state file defaults outside the repository at
`~/.local/state/executespec/enterprise-litmus/current/manifest.json` and is written mode 0600.
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
EXECUTESPEC_LITMUS_PROVIDER=DEEPSEEK \
EXECUTESPEC_LITMUS_MODEL=deepseek-v4-pro-native \
node scripts/enterprise-litmus/litmus.mjs init --manifest /absolute/private/path/manifest.json
```

## Record evidence

The state machine enforces preflight, fixture/project linkage, an explicit sandbox-or-remote-workspace
assessment, two architecture baselines, freeze, three strictly sequential heroes, a complete CLI
fallback proof over those same runs, merge and exact-SHA refresh, continuation, deliberate CI failure
and repair, deployment tracking or a truthful skip, negative tests, and full cleanup. `advance`
cannot skip a state.

```bash
chmod 600 /absolute/private/path/project-linked.json
node scripts/enterprise-litmus/litmus.mjs advance \
  --manifest /absolute/private/path/manifest.json \
  --to PROJECT_LINKED \
  --evidence-file /absolute/private/path/project-linked.json \
  --confirm-external-operation-completed=YES
```

The `PROJECT_LINKED` file must contain exactly `projectId`, `repoId`, and `linkEvidenceId`. Every
other transition similarly rejects missing and unknown fields. The contract in
`materializeEvidence` in `lib.mjs` is authoritative.

## Evidence gates

- Preflight binds the successful deployment workflow run ID/URL/conclusion and host
  `/opt/app/RUNNING_SHA` authority to the same expected/deployed/running server SHA.
- Freeze requires that exact server SHA; fixture original and current-main
  SHAs; ExecuteSpec project/repository/link evidence; two source-bound architecture/code-map/document
  baselines; Web and CLI Git SHAs; an exact provider/model lock; the complete actual project-run
  request (including `projectId` and sorted `selectedRepoIds`) plus canonical request and requirement
  hashes; production/manual approval settings; decision-contract hash; and a positive cost cap.
- Baselines bind code-map, architecture, document source SHA, rubric score, provider/model and
  operation provenance. V2 additionally proves Compare History used a real content diff (not only a
  changed-file list) and a current governed semantic-index unavailability reason degraded to ripgrep
  with zero provider calls. The retired compatibility error remains accepted only for rollout evidence.
- The frozen tuple is digest-bound. No deployment, fixture, project, route, request, approval, or
  client or sandbox-assessment value can change after freeze; invalidate and restart instead.
- `SANDBOX_ASSESSED` consumes the canonical client-capability snapshot. A strict sandbox PASS requires
  every lifecycle/dispatch/strict-runtime/toolchain gate, a digest-pinned image, runtime epoch and
  lease, exact source SHA and mount/UID/GID proof, command/build/test execution with no host fallback,
  reload/history/close proof, project-in-workspace classification, and teardown absence bound back
  to the exact sandbox/runtime epoch/lease/project/source identity. When dev
  exposes only the server workspace, the manifest must say `REMOTE_WORKSPACE_ONLY`, label it
  "Remote workspace", prove no sandbox and no host fallback, and mark it do-not-demo. This evidence
  also records project-context retention plus chat-history snapshot and close availability. It can
  complete the record but adds `SANDBOX_STRICT_RUNTIME_UNAVAILABLE` (and specific history/close or
  project-handoff gaps when present) to the non-PASS requirements and keeps `goEligible=false`.
- Each hero first records a submission and then matching verification. Verification requires
  `COMPLETE`, exact frozen source/request/route, distinct run/branch/approval/PR/head SHA, plan
  ID/version/fingerprint, complete task/build evidence, generated files, passing behavior tests with
  zero failures/skips, truthful review evidence, a physical no-fallback route-call ledger, PR base
  equal to frozen main, exactly one green `fixture-ci` check, bounded cost, byte-exact 151+ line
  output with EOF sentinel, reconnect/reload stability, one primary action plus support code,
  Web/CLI terminal-outcome parity, and independent correctness proof.
- `CLI_FALLBACK_VERIFIED` binds the exact frozen CLI SHA and all three hero run IDs. It requires PAT
  auth and capability discovery; TUI/plain/stdin plus text/JSON/stream-JSON parity; cross-surface
  create/approve against one authoritative run and receipt; attach cursor deduplication; SIGINT exit
  130 with zero server cancel; provider/model/cost/evidence/PR-CI provenance; controlled local-hands
  denial with zero provider/cost/code/PR delta; canonical cwd/realpath/head/manifest evidence; tool
  approval boundaries; and green build, tracked CLI, heavy TUI and Windows smoke evidence. Raw
  output and normalized-result digests are recorded for all six CLI surfaces; normalized results
  must agree, and the harness recomputes one parity digest over those artifacts plus the three hero
  terminal-outcome digests.
- Merge/refresh binds observed main, workspace, architecture, and exact content to the merge SHA.
  Continuation must complete from that SHA. CI repair must green the same PR at a new head SHA while
  preserving the protected-test digest and explicitly proving tests were not weakened.
- Deployment must be either successfully tracked at the CI-fixed SHA or explicitly skipped with a
  decision ID, reason, proof no deployment was created, and proof tracking was checked.
- The registry covers public invalid/reject/cancel/conflict; operator-fixture project-provider
  missing/invalid and matrix-unavailable cases; public policy and the production HTTP-200 SSE
  budget codes `PROJECTED_CALL_BUDGET_EXCEEDED` and `AGENT_TURN_BUDGET_EXHAUSTED` (both projected as
  controlled client status 422 / `ACTION_REQUIRED`); permanent
  workspace-unavailable 410; operator-fixture retryable workspace-restore 503; repository 404;
  tenant 403; auth 401;
  dependency 503;
  local-hands denial; controlled internal failure; and three corruption-only
  operator fixtures. Every public case must be `VERIFIED` with its exact controlled status, zero
  provider/cost/code/PR delta, one action, no raw leak, Web/CLI parity, and distinct delta evidence.
  Only registry entries explicitly marked non-public/operator-fixture may be typed `NOT_RUN`, and must record
  `unsupportedReason`, `requiredAuthority`, and `owner`; the audit lists them as non-PASS.
- Cleanup proves absence of the GitHub repository, ExecuteSpec project, ExecuteSpec repository
  record and link, sandbox, campaign runs, deployment, remote branches, and local artifacts. GitHub deletion alone leaves the
  campaign in `CLEANUP_REQUIRED`.
- `COMPLETE` reruns an independent audit over authoritative fields. Missing, duplicate,
  contradictory, arbitrary, or event-only evidence cannot complete a campaign. The audit separately
  returns `goEligible=false` whenever any allowlisted fixture case is `NOT_RUN` or the strict remote
  sandbox journey is unavailable, so completeness of the record cannot be presented as an
  enterprise-demo GO verdict.

The fixture contains a target function after line 150 and an EOF sentinel. Verification compares
workspace and PR bytes exactly, requires at least 151 lines, and requires the sentinel. Fixture CI
has exactly one job named `fixture-ci`. Apply the committed revision between V1 and V2 with
`patch -p1 -i /absolute/path/to/source-revision.patch`; it adds one tested catalogue behavior
deterministically, so Compare History has a reproducible content hunk instead of an
operator-invented change. `SOURCE_REVISION_PUSHED` binds the patch digest, its two changed paths,
the resulting main SHA, and the single green `fixture-ci` proof.

## Operator-owned work

The harness does not implement authenticated ExecuteSpec project creation/linking,
architecture/document generation, run submission/approval, PR merge, deliberate CI break/fix,
deployment observation, or database-backed negative fixture provisioning. Those operations spend
money or mutate external systems and require reviewed adapters plus live confirmations. `advance`
only records already-completed external work. An allowlisted corruption-only case that cannot be
produced safely remains an explicit non-PASS `NOT_RUN`; it can complete the evidence record but
keeps `goEligible=false`. Publicly executable cases cannot use `NOT_RUN`.
