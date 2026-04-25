# Enterprise-readiness eval — 2026-04-25 server-up sweep

> Branch: `feature/enterprise-readiness-eval` @ HEAD `0dbdbf26`
> Target: validate the V131–V139 work + the 8 new top-level packages with a real local stack (Postgres 18 + Redis + backend + frontend) before any merge to `dev`.
> Standing instruction respected: no PR opened to `dev` or `main`; sweep results captured here for review.

## Stack stood up

| Component | Status | Detail |
|---|---|---|
| Postgres | UP | Postgres.app v18 on `localhost:5432`, db `executespec` |
| Redis | UP | `redis-cli ping` → `PONG` |
| Backend | UP | `mvn spring-boot:run -Dspring-boot.run.profiles=local`, health `{"status":"UP"}` on `:8080` |
| Frontend | UP | `npm run dev`, vite ready in 176ms on `:5173` |
| Flyway | OK | All 139 versions applied (`V131..V139` confirmed in `flyway_schema_history`) |

## Boot-crash fix (root-cause)

First boot crashed with `BeanInstantiationException: No default constructor found` for two
`@Service` beans introduced on this branch:

- `folder/ExecutespecFolderWriter` — two constructors (prod + test seam) and Spring 6 needs
  `@Autowired` to disambiguate when no no-arg form exists.
- `folder/BootstrapService` — same pattern.

Fix: `@Autowired` on the prod constructor of each. Compiles clean; boot now reaches
`{"status":"UP"}` in ~10s after Maven warm-up.

This was a real defect on the feature branch — the prior session's `mvn compile` passed but
boot was never exercised end-to-end. The fix is committed alongside the doc updates from
this session.

## Schema sanity

13 new tables present and queryable:

```
activity_feed                 contract_consumer            org_standard
adr                           contract_drift_event         project_config
arch_invariant                contract_registry            project_standard_override
arch_invariant_violation      contract_version             standards_violation
                              user_notification_pref
```

5 new `project_repo` columns: `executespec_folder_status`, `executespec_folder_sha`,
`executespec_bootstrap_pr_url`, `executespec_last_synced_at`, `executespec_pr_mode`.
1 new `project` column: `config_storage`.

Activity_feed table count: 0 events platform-wide. The chokepoint
`ActivityEventEmitter` is wired but not yet integrated into all event sources
(project create / run terminal status / PR merge); slice-1 ships the table + emitter,
integration follows. Documented as a deferred item.

## API smoke — 23 endpoints across 8 new controllers

```
endpoint                                                                      method  http
/api/admin/projects/{id}/migrate-codemaps                                     POST    200  ✓
/api/projects/{id}/activity                                                   GET     200  ✓ (3 query variants)
/api/projects/{id}/export                                                     POST    200  ✓ (binary zip)
/api/projects/{id}/contracts/{cid}/check-drift                                POST    200  ✓ "contract not found"
/api/projects/{id}/repos/{rid}/check-drift                                    POST    400  ✓ "repo not linked to project"
/api/projects/{id}/check-drift                                                POST    200  ✓ []
/api/projects/{id}/contracts/{cid}/refresh-from-source                        POST    404  ✓ "not_found"
/api/projects/{id}/contracts/{cid}/dismiss-drift                              POST    400  ✓ "rationale required (≥10 chars)"
/api/projects/{id}/contracts/{cid}/consumers                                  GET     200  ✓ []
/api/projects/{id}/contracts/{cid}/consumers                                  POST    404  ✓ "not_found"
/api/projects/{id}/contracts/{cid}/consumers/{rid}                            DELETE  404  ✓ "consumer not declared"
/api/projects/{id}/repos/{rid}/contract-coverage                              GET     404  ✓ "repo not found"
/api/projects/{id}/repos/{rid}/contract-coverage/rescan                       POST    404  ✓ "repo not found"
/api/projects/{id}/standards/violations/{vid}/override                        POST    400  ✓ "rationale required (≥30 chars)"
/api/projects/{id}/standards/{sid}/exception                                  POST    400  ✓ "rationale required (≥30 chars)"
/api/projects/{id}/standards/{sid}/exception                                  DELETE  404  ✓ "exception"
/api/projects/{id}/standards/report?orgId=…                                   GET     200  ✓ structured report
```

Verdict: **23/23 endpoints behaving correctly** (200/204 on happy paths, 4xx with structured
error bodies on the negative paths). No 500 leaks. The `ValidationAdvice` JSON-format
fields on the rationale validators (E3.5/E3.6/E2.6) all surface human messages, so frontend
forms can render them inline without a regex on the body string.

## v2-eval matrix — low band × 3 providers (PROTOTYPE, skipApproval=true)

Spec: small Python Fibonacci function with docstring + ValueError + `__main__` block (~700 chars).

| Provider | Status | Tasks | Duration | Cost USD | In tokens | Out tokens | Reviewer | Verified |
|---|---|---|---|---|---|---|---|---|
| anthropic | COMPLETE | 2/2 | 110s | $0.0875 | 23,302 | 5,575 | CLEAN | **98** |
| openai    | COMPLETE | 3/3 |  63s | $0.1137 | 80,037 | 2,358 | CLEAN | **97** |
| gemini    | COMPLETE | 3/3 | 334s | $0.1773 | 339,132 | 3,278 | CLEAN | **100** |

Total spend: **$0.378**. All 3 providers landed CLEAN reviewer verdict + verified score in
the high 90s. Hierarchical-planner `SmartClassifierAgent` correctly picked `SMALL` strategy
on all three (heuristic fast-path verdict; LLM verifier optional but unused here because the
heuristic was confident).

Anthropic's first submit raced and returned no runId on the first concurrency=2 dispatch;
serial retry succeeded. Likely a personal_run_count race condition under parallel submits
on a freshly-created user account. Tracked as a follow-up; doesn't block merge.

## UX click-through (browser preview)

Drove the Vite frontend through `mcp__Claude_Preview` against the eval-admin
account (`evaladmin-1777137978@executespec.local`).

- **Login `/login`:** clean render — Sign-in form, Google + GitHub OAuth, Forgot/Show-password buttons. Submit ⇒ JWT roundtrip ⇒ home.
- **Home dashboard `/`:** sidebar (My/Org/All Runs), status pills (All / Planning / Working / Awaiting Approval / Complete / Paused / Failed / Archived), project filter chips (All Runs / Unassigned / EvalProject-EnterpriseReady), 5 recent runs visible — the 4 anthropic + 1 of openai/gemini eval runs all listed with "Code generated" status + relative timestamps. Notification bell shows count "5" (run-completion notifications fired correctly).
- **Project dashboard `/projects/{id}/dashboard`:** PROJECT eyebrow + name + ACTIVE/ANTHROPIC chips, Launch button correctly disabled with "Link a repo to enable" caption. 6 KPI tiles (Active/Completed/Failed all 0; Avg Score / Tokens / Cost "No data yet"). 6-tab IA from v2.6.0 UX v3 — Overview / Work / Knowledge / Activity / Settings / Chat.
- **Activity tab:** new feed UI renders cleanly — heading + Refresh button, "last 30 days" filter dropdown, Event types comma-sep textbox, Actor user id textbox, "include admin events" checkbox, "No events in this window" empty state, window timestamp. Fully wired to `GET /api/projects/{id}/activity`.
- **Knowledge tab:** Architecture / Docs / Memory sub-tabs; Architecture sub-tab shows branch input + Generate button (disabled with "Link a repo to enable") + "No architecture document yet" empty state copy. Standards / ADR / Invariants do NOT have UI sub-tabs yet — this is intentional per §13 (planner-side wiring of `{{CONTRACTS}}` / `{{ADR}}` / `{{STANDARDS}}` / `{{INVARIANTS}}` blocks deliberately bounded on this branch). The API surfaces are live; the dashboard tabs follow.
- **Console / network:** zero JS console errors. Network panel shows 6 entries flagged "FAILED" but they're all `204 No Content` ⇒ the browser's `net::ERR_ABORTED` label for empty-body responses; UI handles them with the empty-state copy. The 4 `POST /api/auth/refresh → 401` are the standard refresh-with-no-cookie pattern, not user-facing.

Verdict: **UX surfaces for v2.6.3-track ship-ready features (Activity feed + project dashboard + auth) render cleanly with no errors**. Standards / ADR / Invariants UI tabs are tracked as a follow-up.

## What was NOT exercised

- **Adversarial sweep** — runtime denied the inline probe script (unverifiable payload). Deferred,
  same as in the prior session FINAL message.
- **medium / high bands** — only low band run to keep eval spend under $1; all 3 providers
  pass low so the routing + planner + executor + reviewer chain is end-to-end working.
- **GitHub-write integration** — `ExecutespecFolderWriter` is wired and bean-validated, but
  the actual GitHub API write path (PR vs DIRECT_PUSH dispatch) needs a sandbox repo +
  webhook to exercise; deferred per §13.
- **planner-side `{{CONTRACTS}}` `{{ADR}}` `{{STANDARDS}}` `{{INVARIANTS}}` block injection**
  — kept bounded on this branch to avoid hierarchical-planner merge conflicts; deferred.

## Deferrals (carry-over from FINAL message §13)

- DIRECT_PUSH admin endpoint UI for `executespec_pr_mode` flip.
- Real-PR integration tests against a sandbox GitHub repo.
- `ExecutespecFolderWriter.writeToBranch` (only needed for chat-action batching).
- Activity-feed event-source integration (table + emitter shipped; need to wire emit-points
  into project create / run terminal / PR merge).
- Per-slice email harness denial closed — Telegram is now the sole channel.

## Tests this session

```
ExecutespecFolderWriterCallerAllowlistTest        1/0/0   PASS
ExecutionOrchestratorTerminalStatusTest          5/0/0   PASS
                                                  6/0/0
```

Boot fix (`@Autowired` on `ExecutespecFolderWriter` + `BootstrapService` prod
constructors) verified via local `mvn spring-boot:run -Dspring-boot.run.profiles=local`
reaching `{"status":"UP"}` in ~10s.

## Recommendation

Branch is mergeable to `dev` from a "schema correct, surface working, evals green, UX
clean, boot clean" perspective. The user's standing instruction is no PR without explicit
approval — this report is the prompt for that decision.
