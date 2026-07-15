# ExecuteSpec 2.0 — post-goal adversarial assessment protocol

**Status:** sealed protocol; assessment not yet passed
**Date created:** 2026-07-13
**Scope:** Web/backend release candidate, standalone CLI/TUI 1.0, and their shared contracts
**Purpose:** a hostile, repeatable review after the implementation goal. This is not a release-pass statement, benchmark result, or SOTA claim.

## Rules of evidence

1. A claim is **PASS** only when the current release tuple, command, environment, and artifact are recorded together. A green test from another SHA is historical evidence.
2. A unit test proves the tested branch, not the user journey. Every persona must include at least one live or contract-level scenario.
3. “No obvious bug found” is **UNKNOWN**, never PASS.
4. Provider transport health is not coding quality. Quality claims require a held-out coding task with an observable edit, verification result, and independent oracle.
5. A screenshot proves rendering at one state. It does not prove keyboard navigation, reconnect, responsive interaction, accessibility, or state recovery.
6. Findings are recorded even when they are inconvenient or duplicate another persona. The facilitator deduplicates only after preserving the original observation and evidence path.
7. This protocol is run after implementation and before release sign-off. It does not authorize a push, merge, deploy, or provider spend.

## Release tuple to seal before execution

Record this block in the assessment result, not in the protocol itself:

| Field | Required value |
|---|---|
| Backend/Web commit and tree | `git rev-parse HEAD`, `git write-tree` |
| CLI/TUI commit and tree | same commands in `executespec-cli-1.0` |
| Docs commit | same command in `executespec-docs-2.0` |
| Runtime | Java, Node, PostgreSQL, Redis, browser/terminal and OS versions |
| Feature flags | exported effective flags, including provider and verification flags |
| Auth principal | role/tier only; never record credentials or tokens |
| Evidence root | immutable directory containing raw command output, screenshots/video, API payloads, and hashes |
| Spend ledger | provider/model, request count, cost, budget remaining; zero is a valid result |

If any tuple field is missing, the outcome is **NOT ASSESSED**.

## Severity and decision model

| Severity | Meaning | Release treatment |
|---|---|---|
| P0 | data loss, auth bypass, unbounded spend, stuck production workflow, or unsafe command execution | blocks release |
| P1 | common workflow fails, recovery is misleading, or quality evidence cannot support a product claim | blocks the relevant surface; release only with explicit waiver |
| P2 | material degradation, confusing operator experience, or missing observability | tracked with owner and due date; cannot be silently waived |
| P3 | polish, maintainability, or low-frequency compatibility issue | backlog with reproduction and removal criteria |

Each finding must contain: persona, precondition, exact steps, expected result, observed result, evidence path, severity, confidence (`HIGH/MEDIUM/LOW`), affected surface, owner, and retest condition.

## Persona matrix

| Persona | What they are trying to do | Hostile questions | Minimum evidence | Immediate fail conditions |
|---|---|---|---|---|
| Developer | submit a small change, inspect files/diff/tests, recover from a bad run | Can I understand what changed? Is local authority explicit? Does the tool do what it says? | one BUILD run, one PATCH run, CLI/TUI and Web traces, generated diff + test output | silent mutation, missing changed-file proof, command executed without an approval boundary |
| Senior Developer | solve a medium multi-file change across a real repository | Does planning match the repo? Are failures actionable? Can I retry without duplicating work or spend? | multi-file fixture, task/event ledger, retry/continuation record, final workspace diff | duplicate charge/run, stale workspace, reviewer says clean while build/tests are red |
| Staff Engineer | reason about boundaries, provider routing, persistence, and failure modes | Which component owns the decision? Can a restart or provider switch lose state? Are contracts versioned? | architecture trace, DB rows, SSE replay, provider decision evidence, restart test | implicit coupling, unbounded retry/wait, route switch without policy/audit, non-replayable state |
| Engineering Manager | operate a team using the system at predictable cost and throughput | Can I see queue health, failure causes, spend, ownership, and trend? Are release claims current? | dashboard/API metrics, cost ledger, current-SHA evidence, concurrent-run test | historical green evidence presented as current, no SLO denominator, no owner/action for P0/P1 |
| Product Manager | evaluate whether the product delivers a trustworthy outcome | Does the promise match the behavior? Is score understandable and honest? Where does the user get stuck? | end-to-end journey recording, score evidence ledger, status-reason copy, pricing/provider behavior | marketing claim exceeds evidence, score hides unmeasured signals, dead-end recovery |
| SDET | break correctness, determinism, and recovery | What happens on malformed input, red tests, reconnect, duplicate submit, restart, timeout, and partial provider failure? | deterministic tests + API traces + replayable fixtures | race, data corruption, false PASS, irreproducible failure, unbounded loop |
| SDET Manager | decide whether the quality system is trustworthy at scale | Are controls measurable, independent, and resistant to gaming? Are negative tests first-class? | coverage map, mutation/negative cases, flake report, independent oracle, pass/fail ledger | test suite only exercises happy path, fail-open behavior is unlabelled, no flake/coverage denominator |
| UX Researcher | observe first-time and expert users completing work | Where do users hesitate, misread state, or lose trust? Does desktop/mobile/TUI work with keyboard? | moderated task script, screen/video capture, task time/errors, accessibility notes | user cannot tell what is running/waiting/blocked, irreversible action lacks context, mobile is decorative only |

## Adversarial scenario catalogue

The facilitator runs the scenarios below for each applicable persona. IDs are stable so results can be compared across releases.

### Identity, tenancy, and safety

| ID | Scenario | Expected invariant |
|---|---|---|
| SEC-01 | replay expired JWT/PAT and scoped SSE token | request is rejected; no partial mutation or leaked run data |
| SEC-02 | cross-tenant run/project/report/event access | every read and write is denied; no existence leak beyond policy |
| SEC-03 | submit local-hands mode from browser JWT | rejected; PAT/local client remains the only allowed authority |
| SEC-04 | malicious path, shell, prompt, encoded or zero-width input | sanitizer/scanner/sandbox rejects or safely contains it with evidence |
| SEC-05 | duplicate submit with same idempotency key | exactly one run and one charge; response is replayable |

### Run lifecycle and recovery

| ID | Scenario | Expected invariant |
|---|---|---|
| RUN-01 | hold approval gate while unrelated runs execute | no executor starvation; unrelated run makes progress |
| RUN-02 | approve, reject, cancel, and approve-after-cancel races | one terminal decision; no duplicate continuation or charge |
| RUN-03 | SSE disconnect/reconnect and event-log backfill | typed events and final evidence converge after reload |
| RUN-04 | JVM restart at awaiting approval, during worker, and after terminal write | durable state resumes or fails explicitly; no phantom success |
| RUN-05 | build red, tests red, reviewer warning, and provider timeout | status reason names the actual failure; score caps remain visible |
| RUN-06 | task retry and continuation approval | only intended task reruns; counters, spend, and action history persist |
| RUN-07 | workspace drift or changed source SHA | system detects/requires sync; it does not silently overwrite user work |

### Agent quality and routing

| ID | Scenario | Expected invariant |
|---|---|---|
| AGT-01 | small single-file request with explicit acceptance test | correct edit, test, and completion proof |
| AGT-02 | multi-file request with hidden coupling | plan identifies affected files; reviewer catches missing wiring |
| AGT-03 | clean/no-op transform request | detection-first behavior; no destructive rewrite |
| AGT-04 | model/provider failure mid-task | bounded retry/failover follows policy; selected route and reason are persisted |
| AGT-05 | low-confidence completion after edits | system does not present it as clean; continuation/approval evidence is explicit |
| AGT-06 | provider comparison on a held-out coding fixture | compare edit correctness, tests, latency, cost, and failure mode—not transport 200s |
| AGT-07 | prompt/tool schema mismatch or missing overlay | fail closed or retain base prompt; no silent malformed tool contract |

### Client and UX surface

| ID | Scenario | Expected invariant |
|---|---|---|
| UX-01 | first-run Web compose → plan gate → completion | user can explain current state without internal jargon |
| UX-02 | failed run → inspect evidence → retry/iterate | recovery path is discoverable and does not discard context |
| UX-03 | Web reload during stream and after completion | run card, cost, evidence, and status restore from server state |
| UX-04 | 390px mobile width, keyboard-only desktop, reduced motion | no clipped controls, trapped focus, or hidden critical state |
| UX-05 | CLI/TUI chat, plan gate, local approval, Ctrl-C, reconnect | visual hierarchy and safety prompts remain legible in a real terminal |
| UX-06 | user compares score and raw verification evidence | score is framed as summary; deterministic vs LLM evidence is distinguishable |
| UX-07 | provider/model switch or managed-credit exhaustion | user understands why route changed and what action is required |

## Measurement sheet

For every scenario, record one row in a result file with this schema:

```json
{
  "scenarioId": "RUN-03",
  "persona": "SDET",
  "surface": "web|cli|backend|cross-surface",
  "tuple": {"commit": "", "tree": "", "environment": ""},
  "preconditions": [],
  "steps": [],
  "expected": "",
  "observed": "",
  "result": "PASS|FAIL|UNKNOWN|NOT_ASSESSED",
  "severity": "P0|P1|P2|P3",
  "confidence": "HIGH|MEDIUM|LOW",
  "evidence": ["relative/path"],
  "cost": {"provider": "", "model": "", "usd": null},
  "retest": "",
  "owner": ""
}
```

Do not calculate a single “persona score.” Report: pass count, fail count, unknown count, scenario coverage, P0/P1 count, and evidence completeness. A high pass percentage with many UNKNOWN rows is not a pass.

## Execution order and independent checks

1. **Seal tuple and freeze code.** Capture SHAs, trees, flags, runtime versions, and clean/dirty state.
2. **Run deterministic checks.** Backend tests/verify, frontend tests/build/lint/format, CLI/core/VS Code tests and TUI QA, docs tests/integrity. Store raw output.
3. **Run API adversarial checks.** Auth, tenancy, idempotency, lifecycle races, reconnect, restart, scoring/report evidence, and cost ledger.
4. **Run coding-quality checks.** Use a held-out fixture and independent verifier. Keep provider spend within the approved per-provider cap. A transport probe is supplementary only.
5. **Run UX research checks.** Desktop, mobile, keyboard, native terminal, reconnect, and failure recovery. Capture interaction—not just static screenshots.
6. **Cross-review findings.** Each P0/P1 gets a second persona review. Disagreements remain visible.
7. **Close or waive.** A waiver needs named owner, rationale, expiry/retest condition, and user-visible limitation. “Not enough time” is not a waiver rationale.

## Current known risks to carry into the run

These are starting hypotheses, not findings for the current tuple:

- Large orchestration classes and the remaining API/UI coupling may hide regressions even though focused cleanup and current tests are green.
- Verification has bounded fail-open behavior; the assessment must measure and label red-at-complete, repair-pass, and unmeasured-test outcomes.
- Historical persona evidence exists, but older documents may describe removed gate-waiting or stale SHAs. Re-test against the sealed tuple; do not copy those findings forward as current facts.
- Web visual evidence must include current-tuple interaction/recovery, not only a prior screenshot set. Native CLI evidence does not substitute for Web evidence.
- Provider readiness and provider coding quality remain separate claims; only the latter can support parity positioning.

## Exit criteria for the post-goal assessment

The assessment is complete only when:

- all applicable scenarios have `PASS`, `FAIL`, or an explicitly accepted `UNKNOWN/NOT_ASSESSED` with owner and retest condition;
- no unexplained P0 exists and every P1 has a fix or time-bounded waiver;
- current tuple and raw evidence are immutable and independently readable;
- Web, CLI/TUI, backend, and cross-surface findings are represented;
- at least one independent reviewer challenges the facilitator’s interpretation;
- the final report states what is *not* proven (especially SOTA, provider parity, and benchmark generalization).

This protocol itself never changes a release to PASS. It supplies the evidence needed for the release decision.
