# Live provider handoff cohort — blocked safely

Date: 2026-07-15  
Scope requested: native DeepSeek baseline versus one fallback, three fixed fixtures
(repository localization, edit/test/repair, and deliberate stuck/handoff), at least
three paired attempts, no uncontrolled failover, and a maximum of $2 per provider.

## Decision

**No provider calls were dispatched.** The current live harness cannot enforce this
cohort without source changes or an unbounded ad-hoc runner, so spending was stopped
before reservation.

## Exact blocker

The only current authenticated coding endpoint is:

```text
GET /api/v2/test/low-coding
  provider, model, runId, reservationId
```

`LowCodingFixtureController` accepts no fixture, task, evaluator, handoff trigger, or
failover-policy parameter. It always invokes
`LowCodingFixtureHarness.FIXTURE_ID=low-python-addition-v1`, which creates only
`calculator.py` and asks the worker to change subtraction to addition. The harness
exposes fixed LOW caps and fingerprints, but has no repository-localization fixture,
edit/test/repair fixture, or deterministic stuck-task/handoff fixture. It also does
not expose a way to force or observe a selected fallback in the same task while
prohibiting unrelated failover.

Therefore, a run against this endpoint could only produce another addition-only
cohort. Calling providers and labelling those results as localization, repair, or
handoff quality would be invalid evidence.

## Budget state at refusal

The canonical provider ledger was checked before any reservation:

- all provider reservations: `0`
- all provider locks: `false`
- no ledger delta from this audit
- no Sarvam, Gemini, OpenRouter, or Fireworks call was attempted

The ledger remains the authoritative spend source at
`~/.local/state/executespec/goals/executespec-2.0-cli-1.0/provider-spend-ledger.json`.

## Existing evidence boundary

The existing 2026-07-15 low-coding cohort is valid only for its one deterministic
addition fixture (three attempts per provider, independent held-out checks). It does
not establish repository localization, edit/test/repair, handoff uplift, or broad
provider ranking. The deterministic continuation suite is mocked and does not prove
live provider uplift.

## Required unblock

Add an operator-only, reservation-gated harness that accepts a versioned fixture set
and an explicit handoff trigger/expected route, records every provider/model request,
and disables all routes outside `{deepseek-native, chosen-fallback}` for the run. Then
rerun the fixed fixtures with paired attempts and independent held-out evaluators.

