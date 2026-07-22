# Brownfield remediation validation — 2026-07-22

## Verdict

The branch is locally mergeable with one P0 auth defect found and repaired during validation. Backend, frontend, and standalone CLI gates pass. Cross-tenant run and cost access remained isolated across three live orgs. The remaining gaps are fixture- or environment-limited and are listed below; they are not represented as passes.

## Scope and traceability

- Branch history inspected: 128 commits and 61 merge commits.
- RR inventory: 653 distinct RR identifiers occur across the branch and remediation ledger; 235 are explicitly referenced by merge commit bodies. An occurrence is not treated as proof of closure. The audit therefore uses implementation, focused tests, live API behavior, or UI evidence for high-risk claims and reports the remainder by subsystem.
- RR-303 is preview boot-verification fail-open in the ledger, not idempotency. Run submission idempotency was validated independently through the API.
- High-risk surfaces inspected or exercised: authentication/session revocation, RBAC and tenant guards, cost visibility, run admission and plan approval, idempotency, SSE reconnect, webhook rejection, run-result score truthfulness, responsive Console routes, command-palette accessibility, and standalone CLI behavior.

## Defect found and fixed

### Logout left access JWTs active (RR-196 acceptance gap)

Before the repair, logout returned 204 and revoked the refresh token, but the pre-logout access JWT still authenticated `/api/auth/me` with 200. The repair advances `AppUser.tokenInvalidatedAt` at browser logout and keeps refresh rotation on a narrower JTI-only revocation path. A full-precision `issued_at` claim avoids rejecting a legitimate re-login minted in the same NumericDate second.

Corrected live sequence: initial `/me` 200; logout 204; old access JWT 401; old refresh cookie 401; immediate re-login `/me` 200. Regression tests cover both old-token rejection and same-second new-token acceptance.

## Validation results

| Surface | Result | Evidence |
|---|---|---|
| Backend unit/contract suite | PASS | 785 report files, 4,678 tests, 0 failures, 0 errors, 0 skipped |
| Backend formatting | PASS | `spotless:check` and `git diff --check` |
| Frontend build/tests/lint/format | PASS | build; 390 tests; lint 0 errors (4 pre-existing warnings); format check |
| CLI build/typecheck/lint/format | PASS | standalone `executespec-cli` repository |
| CLI tests | PASS | 996 passed, 2 skipped, 0 failed |
| API assertions | PASS after repair | 41 checks; initial logout defect repaired and live-retested |
| Cross-tenant isolation | PASS for exercised matrix | FREE/PRO/TEAM own detail+cost 200; all six cross-org combinations 403; visible run-ID intersections 0 |
| Submission idempotency | PASS | same key/body returned same run; changed body 409; failed claim replay 409 |
| Production plan gate | PASS | run parked at approval; gate survived reload/restart; stale fingerprint 409; exact fingerprint accepted |
| Live DeepSeek execution | BLOCKED / NOT VALIDATED | clean runs stopped before provider dispatch because local live transport required reserved provider budget; 0 tokens, $0, no tool calls |
| SSE reconnect | PASS locally | same scoped token established two sequential 200 streams |
| Webhook negative security | PASS | missing/bad GitHub signature and missing Stripe/Razorpay signatures rejected |
| Console route crawl | PASS | eight protected routes at desktop and 375px; representative 768px checks; no console errors |
| Score truthfulness | PASS | cancelled run without report displayed no verified score and no shippable/push/PR affordance |
| Command palette accessibility | PASS | combobox/listbox relationship, expanded state, autocomplete, active descendant |

## UI evidence

Screenshots are stored outside the repository in the Codex visualization artifact directory:

- `desktop-home.png`, `desktop-compose.png`
- `mobile-375-home.png`, `mobile-375-compose.png`
- `tablet-768-home.png`, `tablet-768-compose.png`
- `desktop-no-score-run.png`
- `desktop-production-plan-gate.png`

The desktop crawl covered `/home`, `/compose`, `/agent`, `/runs`, `/projects`, `/org-members`, `/setup`, and `/notifications`. The same routes rendered at 375×667 with no console errors. Home and Compose were also checked at 768×1024. No SOC 2 or GDPR certification claim appeared on the inspected org surface.

## Honest limitations

- The documented ENTERPRISE and SUPER_ADMIN local credentials returned 401 even after narrow password-hash fixture repair. Enterprise/BYOK exemption, missing-tier privileged mutation, and SUPER_ADMIN-only live paths were therefore not dynamically proven.
- Valid-signature webhook replay was not exercised because no local signing secret was provided. Negative signature enforcement was exercised.
- RR-1551 was proven through live cross-org access isolation, independent tenant estimates, and source predicates requiring `orgId`; a controlled foreign-history injection was not performed.
- The local dataset contained no TEAM project with two linked repositories, so a true multi-repository execution was not reachable.
- The first live execution attempt overlapped Maven rebuilding `target/classes` and produced `NoClassDefFoundError`; those failures are invalidated as environment artifacts. The subsequent clean-backend rerun validated orchestration and the production approval gate, but live generation remained blocked before dispatch by the local provider-budget reservation policy. It is not recorded as a DeepSeek quality pass or failure.
- FREE submissions projected `deepseek-v4-flash-native` at the run level while persisted LOW task routing selected the WORKHORSE `deepseek-v4-pro-native` before dispatch. Because transport stopped before a provider call, whether managed-AI enforcement would step this down at the spend chokepoint was not dynamically proven.
- The local CLI live smoke used the FREE identity. Cross-tier CLI behavior relies on the same server authorization contracts but was not separately repeated for every tier.

## Local evidence index

- `.codex-work/validation/api/API_VALIDATION_REPORT.md`
- `.codex-work/validation/run-matrix/REPORT.md`
- Founder escalation log supplied for decision context: `/Users/navneetprabhakar/Downloads/executespec-pending-founder-decisions.md`

The `.codex-work` evidence contains sanitized summaries only; bearer tokens, refresh cookies, PAT plaintext, and passwords were removed.
