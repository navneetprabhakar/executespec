# Strict Eval Scenarios v2

> Replaces the existing `scripts/v2-eval-specs/{low,medium,high}.txt` triad.
> Goal: shift the eval from "did the build pass?" toward "does the artefact
> meet a fully specified, machine-verifiable contract?". Tightens criteria
> across functional correctness, contract conformance, security hygiene,
> performance, observability, and DX.

## Why the existing triad is too loose

| Tier | Existing spec (excerpt) | Failure mode it allows |
|---|---|---|
| **low** | "Write a Python function that computes the nth Fibonacci number iteratively…" | Passes if `fib.py` exists and `__main__` runs; doesn't test edge cases (n=0, n=1, very large n), doesn't enforce iterative-only, no perf bound. |
| **medium** | "Build a minimal Express REST API for a to-do list…" | No spec for input validation messages, status codes for malformed JSON, idempotency, ordering tie-breakers, error response shape, ETag/concurrency. |
| **high** | (not read here, but per CLAUDE.md is the URL shortener) | Smoke test = `mvn compile` / `npm run build`; no integration tests asserted; LLM scores itself. |

The cost-quality story we want from the eval is *"does the run produce
something a senior reviewer would merge?"*. The current triad only answers
"does it parse?".

## Strict scoring rubric (replaces ad-hoc `accuracyScore`)

Every spec below ships with an explicit, machine-checkable acceptance suite.
The score is the weighted sum of suite outcomes, never the LLM's
self-assessment:

| Dimension | Weight | What's measured |
|---|---:|---|
| **Functional correctness** | 30 | Acceptance suite pass rate (each spec ships with a `tests/acceptance.spec.*`). Binary per case. |
| **Contract conformance** | 20 | Endpoints / function signatures / CLI flags exist with the *exact* names + status codes + response shapes. Validated by replay against `contract.json`. |
| **Security hygiene** | 15 | No secrets in source, no `eval`/`exec`/shell-out without an allowlist, no SQL string-concat, password fields hashed if present. Run `gitleaks` + `semgrep --config=auto`. |
| **Performance budget** | 10 | p50/p95 latency for the hottest endpoint under 100 RPS for 30 s; memory ceiling per process. |
| **Observability** | 10 | Structured logs with `traceId`, basic metrics, health endpoint, version stamp. |
| **DX & repo hygiene** | 10 | `README.md` with install + run + test, `.gitignore`, lockfile committed, no committed `node_modules`/`__pycache__`, build cleanly under fresh clone. |
| **Test quality** | 5 | Generated tests exist, are non-trivial (≥ 3 cases per endpoint, includes a negative case). |

A run scores **PASS** only if Functional ≥ 90 % and *every other dimension*
≥ 60 %. PARTIAL otherwise. FAIL if Functional < 50 %.

The reviewer LLM continues to do qualitative reads but its score is no longer
the score of record. The verified score column added in V106 (`scoring.v2-verified`
flag) becomes the authoritative number; the eval harness asserts on that.

## Scenarios

### S1 — TINY (≤ 1 file, deterministic) — *replaces* `low.txt`

**Spec:** Implement an iterative `fib(n: int) -> int` in `fib.py` that:
- raises `ValueError` for `n < 0` with the *exact* message `"n must be non-negative"`
- returns 0 for `n == 0`, 1 for `n == 1`
- runs in O(n) time, O(1) space (no recursion, no memo dict)
- the `__main__` block prints the first 10 numbers as `f"fib({i}) = {value}"`
  one per line

**Acceptance harness (`tests/test_fib.py`)** — committed in eval scaffold:
```python
import fib, pytest
@pytest.mark.parametrize("n,exp", [(0,0),(1,1),(2,1),(10,55),(30,832040)])
def test_values(n, exp): assert fib.fib(n) == exp
def test_negative():
    with pytest.raises(ValueError, match="n must be non-negative"):
        fib.fib(-1)
def test_main(capsys):
    fib.__main__ if False else exec(open("fib.py").read(), {"__name__":"__main__"})
    out = capsys.readouterr().out.strip().splitlines()
    assert len(out) == 10
    assert out[0] == "fib(0) = 0"
    assert out[9] == "fib(9) = 34"
```
Plus `static`: assert no `def fib(n)` body contains `fib(n-1)` or `fib(n-2)` (rules out recursion).

**Pass bar:** Functional 100 / Contract 100 / Hygiene any / Test 100. Anything below = FAIL.

### S2 — SMALL (single endpoint with edge cases) — *new*

**Spec:** Implement `POST /shorten` in any language. Body `{ "url": "https://…" }`.
- 201 with `{ "id": "<8-char base62>", "shortUrl": "/<id>", "url": "<original>" }` on valid input
- 400 with `{ "error": "invalid_url" }` for missing/blank/non-`http(s)` URLs
- 409 with `{ "error": "duplicate", "id": "<existing>" }` if the same canonical URL was already shortened in this process lifetime
- in-memory store; idempotency for identical URLs
- include `traceId` (UUID) on every response header `X-Trace-Id`

**Acceptance harness:** 12 black-box HTTP cases (valid, blank, non-http, idempotent, repeated, header presence, ID format, ID length, JSON shape, content-type, 400 shape, 409 shape).

**Pass bar:** Functional 100, Contract 100, Hygiene ≥ 60, Observability ≥ 80
(trace header is part of observability).

### S3 — MEDIUM (CRUD + persistence + validation) — *replaces* `medium.txt`

**Spec:** Express to-do API as before, *plus*:
- Reject `Content-Type` other than `application/json` with 415.
- Reject body > 16 KB with 413.
- POST `/todos` rejects unknown fields with `400 { error: "unknown_field", field: "<name>" }`.
- Sort order on `GET /todos`: `createdAt DESC, id ASC` for stable ordering when two records share the same millisecond.
- ETag on `GET /todos/:id`; PATCH must accept `If-Match` and return `412` on mismatch.
- README contains a `curl` example for each endpoint that copy-pastes and works.
- `npm test` runs the supplied acceptance harness and exits 0.

**Acceptance harness:** 24 cases including 415, 413, 412, ordering tie-break, unknown-field reject, CORS preflight (must NOT be enabled by default — the spec is explicit), JSON-error-on-malformed-body.

**Pass bar:** Functional ≥ 90, Contract 100 (status codes are part of the contract), Hygiene ≥ 60, Test ≥ 80.

### S4 — LARGE (multi-file with dependency graph) — *replaces* `high.txt`

**Spec:** URL shortener service with TTL + rate-limit (existing high.txt baseline)
*plus*:
- Persistence layer abstracted behind a `Store` interface; ship two
  implementations: in-memory and SQLite. Selectable via `STORE` env var.
- Rate-limit headers on every response: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.
- Background sweeper deletes expired entries every 60 s; expose an internal
  `/_admin/sweep` POST gated by a static `X-Admin-Token` header for tests.
- Structured logging (JSON) with at minimum: `ts`, `level`, `traceId`, `route`, `latencyMs`.
- 70 %+ line coverage on the supplied tests OR self-generated tests.

**Acceptance harness:** 40+ cases covering both Store implementations,
rate-limit boundaries, TTL expiry semantics, sweeper idempotency, log format
shape (parseable JSON, has required fields).

**Pass bar:** Functional ≥ 85, Contract 100, Performance ≥ 70 (latency under
load), Test ≥ 70.

### S5 — INTEGRATION (multi-repo, project-shell) — *new for project surface*

**Setup:** Project with two empty repos (`*-be` Node + `*-fe` React) created
fresh per run. Spec is a single sentence: *"Build a sign-up form that POSTs to
/api/users and shows the new user's email + a 'Welcome' heading on success."*

The eval verifies:
- The planner emits ≥ 1 task per repo.
- The backend repo gets `POST /api/users` with `{ email, password }` →
  201 `{ id, email }` validation present.
- The frontend repo renders a form, posts to `/api/users`, displays the
  success state.
- Both repos build (`npm run build` in each).
- A live preview comes up and the acceptance Playwright script
  (`tests/e2e.spec.ts`) passes.

**Pass bar:** Functional ≥ 80, Contract 100, Performance any, Test any. This
is a project-shell stress test, not a perf test.

## Provider × model matrix to run

| Tier | Provider | Model under test | Why |
|---|---|---|---|
| LIGHTWEIGHT | Anthropic | `claude-haiku-4-5-20251001` | current default |
| WORKHORSE | Anthropic | `claude-haiku-4-5-20251001` | current; check ceiling |
| PLANNING | Anthropic | `claude-sonnet-4-6` | per `application.yml` model-matrix; do **not** switch to Opus 4.7 yet |
| LIGHTWEIGHT | OpenAI | `gpt-5.4` | only available in matrix |
| WORKHORSE | OpenAI | `gpt-5.4` | same |
| PLANNING | OpenAI | `gpt-5.4` | same |
| LIGHTWEIGHT | Gemini | `gemini-3-flash-preview` | only available |
| WORKHORSE | Gemini | `gemini-3-flash-preview` | same |
| PLANNING | Gemini | `gemini-3-flash-preview` | same |

OpenAI and Gemini collapse to a single model per provider in the live
`/api/models/available` response. **Suggestion to product:** before next eval,
expose a *cheap* and a *premium* per provider so the matrix has 2-3 cells per
provider rather than 1. Candidates worth wiring:
- OpenAI `gpt-5.4-mini` for LIGHTWEIGHT, `gpt-5.4` for WORKHORSE/PLANNING.
- Gemini `gemini-3-pro-preview` for PLANNING (separate from Flash).

Until those land, the eval will not show *intra-provider* tier differentiation
for OpenAI / Gemini; only Anthropic exhibits both Haiku and Opus.

## Execution plan for *this* eval pass (budget-bound to $25 total)

1. **S1 (TINY)** × 3 providers — expected ~$0.10 / run = **$0.30**.
2. **S2 (SMALL)** × 3 providers — expected ~$0.40 / run = **$1.20**.
3. **S3 (MEDIUM)** × 3 providers — expected ~$1.50 / run = **$4.50** (skip Gemini if budget tight).
4. **S5 (INTEGRATION)** × 1 provider (cheapest) — expected ~$2.00 = **$2.00**.

Subtotal target: **$8.00**, well inside the $25 cap. The remaining ~$17 is
held back for retries, S4 if S3 reveals strong outliers, and the project-shell
test scenarios.

If a single S3 run blows past $5 (which the existing $1.73 PARTIAL run shows
is possible), abort the rest of the matrix and report what we have.

## Reporting format

For every run we capture:
- Total tokens, total cost, wall time
- Verified score (V106 column), accuracy score (LLM), reviewer verdict, status_reason
- Per-dimension scores from the strict rubric above (computed offline from
  the produced workspace — see `scripts/strict-eval/score.py` to be added)
- Critical issues from the validation report
- Whether the smoke build/test passed

Output: a markdown comparison table per scenario, plus a per-provider
narrative section. Final report at `docs/reports/eval-results.md`.

## Out of scope

- Streaming output latency comparison (would need timing instrumentation we
  don't have today).
- Cross-provider fallback behaviour (deliberately disabled with
  `-Dexecutespec.strict-provider=true` for the eval).
- Batch-mode pricing — V2 batch isn't implemented yet
  (`docs/plans/v2-batch-execution-plan.md`).
