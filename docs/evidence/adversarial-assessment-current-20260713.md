# ExecuteSpec 2.0 / CLI-TUI 1.0 — hostile persona assessment (current tuple)

**Status:** `ASSESSMENT_ONLY` / release not approved  
**Captured:** 2026-07-13 (Asia/Kolkata)  
**Method:** source and evidence review against the frozen candidate; no new provider calls; no production mutation.

This is the requested adversarial review from eight operating perspectives. It is deliberately harsher than the product's run score. The ratings below are **evidence-completeness ratings**, not model quality scores and not a replacement for the protocol's required PASS/FAIL/UNKNOWN rows.

## Candidate tuple

| Surface | Commit | Tree |
|---|---|---|
| Backend/Web | `3da8c0057cd1567d7095cf56c1e889e10f930791` | `354cf4641798aa101cc623bf1c3987b01ec16035` |
| CLI/TUI | `772aa7485bc991ccc9346028fbe55ecd56b2773b` | `b420ab5b37ee920320062f5e5834c45f741d46bc` |
| Docs | `fc2d8cfa92e71728228f62e42177b9d67b87b830` | `8447ea2006d395e27ed417a76cfe98b7537ab96c` |
| Database | Flyway `V272` |

No credentials, tokens, or provider payloads are included here. The current hero recording is provider-free and explicitly has `independentHumanReview=PENDING`; it is not UX or coding-quality proof.

## Rating scale

`5` means the applicable scenarios have current, independently readable evidence with no material unknown; `4` means mostly evidenced with only P2 gaps; `3` means deterministic controls are credible but a material P1 scenario is unproved; `2` means useful artifacts exist but a core daily workflow or quality claim is unverified; `1` means mostly historical/indirect evidence; `0` means not assessed. This scale must not be averaged into a release score.

## Persona matrix

| Persona | Evidence rating | Current PASS evidence | UNKNOWN / FAIL evidence | Highest finding |
|---|---:|---|---|---|
| Developer | 2/5 | Authenticated run/replay fixtures; local search; explicit approval boundaries; diff/test evidence surfaces | No held-out edit→test→repair cohort; diff lacks IDE-grade syntax/hunk/test linkage; large-repository semantic search unmeasured | P1 quality evidence and P1 review ergonomics |
| Senior Developer | 2/5 | Durable provider/model decisions, task continuation records, replay cursors, bounded recovery | No repeated provider escalation proving quality uplift; no cross-JVM takeover or merge-conflict recovery; fork semantics can omit execution evidence by design | P1 continuation/recovery confidence |
| Staff Engineer | 3/5 | Tenant/access coverage, typed agent-session protocol, first-writer fencing, Flyway upgrade rehearsal, bounded subagent execution | No production-scale large-repo load; no cross-JVM workspace handoff; multiple routing seams still require operational discipline | P1 reliability/scalability evidence |
| Engineering Manager | 2/5 | Current SHA/tree manifests, spend reservations/reconciliation, explicit release blockers, SLO definitions | No current denominator for throughput/quality SLOs; promotion is manually coordinated across repos; flag/surface count remains high | P1 release-governance evidence |
| Product Manager | 2/5 | Chat-first Web flow, status reasons, score evidence ledger, honest provider/SOTA disclaimers, current illustrative recording | Benchmark parity, time-to-useful-edit, retention, and daily-driver success unmeasured; recording lacks independent sign-off | P1 product-claim overreach |
| SDET | 3/5 | Backend/CLI deterministic suites, idempotency and replay tests, migration tests, guarded fixtures, first-writer race coverage | No hostile restart/network interruption matrix on current tuple; verification fail-open/red-at-complete rates not measured; no repeated independent oracle | P1 adversarial coverage gap |
| SDET Manager | 2/5 | Evidence provenance rules, provider spend guard, independent-vs-system comparison artifact, explicit unknown classification | No flake/coverage denominator or mutation score; no independent held-out cohort at scale; system scores overstate limited cases | P1 trust/measurement gap |
| UX Researcher | 2/5 | Current Web desktop/mobile states; native TUI frame and repaint fixes; transcript-first information architecture | No moderated user study, keyboard-to-paint measurement, mobile interaction/reconnect observation, or native Terminal human sign-off; diff is not IDE-grade | P1 usability validation gap |

### Score interpretation

The matrix is intentionally low despite many green tests: it measures proof of a daily-driver frontier assistant, not implementation effort. The system is strongest on deterministic contracts and weakest on independent outcome evidence and human interaction evidence.

## Concrete findings

### P0 — none evidenced in this review

I found no current-tuple evidence of an auth bypass, unbounded provider spend, silent unsafe shell execution, or irreversible data loss. This is not a proof that P0 risk is impossible; the hostile P0 scenarios remain required before release.

### P1 — release-relevant

1. **Coding quality is still not independently proven.** The five-provider `low-python-addition-v1` matrix has one matched fixture and explicitly says it is capability evidence only. The Anthropic WORKHORSE artifact has three cases, but independent review scored 95/95/90 versus system 100/100/100 because no artifact-level or held-out verifier was attached. The mean absolute disagreement is **6.67 points**, and the system overstates every case. Evidence: `../executespec-docs-2.0/release-evidence/low-coding-provider-matrix-20260713.json` and `anthropic-workhorse-independent-scoring-20260713.json`.

2. **TUI is deterministic-test strong but not world-class UX proven.** Full-frame invalidation and black-frame rejection reduce stale captures, but native Terminal evidence remains supplementary. There is no keyboard-to-first-paint metric, moderated study, or proof that the transcript/diff loop beats OpenCode/Claude Code for a daily user. Diff review is bounded but still lacks syntax-aware highlighting, hunk navigation/revert, and test-result linkage. Evidence: CLI `docs/evidence/adversarial-cli-personas-2026-07-12.md` and current release-readiness rows.

3. **Cross-surface and restart recovery remain incomplete as user journeys.** API cursor replay and first-writer fencing are tested, but hostile network interruption, JVM restart during each lifecycle phase, cross-device takeover, and a browser-originated stream compared with CLI replay are not all evidenced on this tuple.

4. **Automatic verification is bounded, not outcome-proven.** The server-owned verify-before-complete path can fail open after its bounded repair opportunity. There is no current red-at-complete, repair-pass-rate, or verification-latency cohort by provider; the in-loop test tool remains off because earlier evidence showed DeepSeek no-edit stalls.

5. **Release governance is still manual and multi-surface.** Current tuple evidence is correct in this report, but several historical manifests remain in the repositories. There is no single immutable promotion gate that rejects stale SHA/tree rows across backend, CLI, Web captures, and docs.

6. **Human UX validation is absent.** Screenshots and provider-free recordings prove rendering, not comprehension, keyboard discoverability, accessibility, mobile action completion, or trust after failure/reconnect.

### P2 — material but not immediate blockers

- Local search is lexical with deterministic symbol fallback; semantic retrieval and 10k+ real-repository recall/latency are unmeasured.
- Multiple routing/model-resolution seams and many feature flags increase drift and make cleanup dependent on discipline.
- Subagent execution is bounded and persisted, but automatic delegation and mutation-capable uplift/conflict-recovery cohorts are absent.
- CLI timeline and event surfaces intentionally cap visible history; cursor gaps/resync boundaries should be surfaced more prominently.
- Legacy compatibility paths and large frontend/API modules remain costly to reason about and test.

## Self-rating versus system score

The product's `verifiedScore` is a run-level summary of code-delivered/build/tests/reviewer/critical slots. It is not a release-readiness or independent-correctness score. On the only current independent comparison inspected, system average was `100`, independent average `93.33`, disagreement `6.67`; all three cases were downgraded for missing artifact-level proof. Therefore I would not translate a clean run score into “frontier parity.”

## Decision

`NO_RELEASE_CLAIM`. No P0 was evidenced, but the P1 quality, recovery, UX, and release-governance gaps remain open. Required next evidence: a blinded held-out edit/test/repair cohort with an independent oracle; hostile Web/CLI/restart recovery traces; human keyboard/mobile/native-TUI review; and a single current-tuple promotion validator. FastContext and SOTA comparator remain explicitly parked/non-blocking, but must not be described as proven.

