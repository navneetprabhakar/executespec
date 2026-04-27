# Greenfield Execution — Wave 2 Kickoff

> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).
> Wave 1 — 20/20 R-IDs shipped (commit `a8841f11`).
> **Wave 2 — 0/11 R-IDs in flight.** R-125 added mid-stream after the user's
> "how does someone add a repo?" review surfaced the cohabitation gap.

## Wave 2 sequencing (the order this session executes)

1. **R-141** `/v3/billing` — 3-tier card grid + usage + receipts (consumes R-142 entitlements; biggest unblocker for Maya/Devon/Amara)
2. **R-150** `/v3/pricing` — public peer of billing (logged-out path)
3. **R-163** Free-cap upgrade nudge modal (pairs with R-141)
4. **R-125** Repo-attach popover (closes the v2-cohabitation gap surfaced in user review)
5. **R-144** `/v3/integrations` grid (peer of R-125 — fallback path for "Connect a GitHub repo")
6. **R-143** `/v3/team` — members + seats + invite + SSO panel
7. **R-162** Referral mechanism (consumes V152 `referred_by_user_id`; pairs with R-163's invite path)
8. **R-151** `/v3/trust` — procurement-grade Trust page (Amara unlock)
9. **R-152** `/v3/insights` — collapsed analytics screen (Devon unlock)
10. **R-153** HelpModal — Cmd-K `?` mode (extends R-103's palette)
11. **R-170** Mobile responsive primitives sweep (last — needs every screen to exist)

## Decisions parked with defaults (user can override; auto-mode applies defaults)

| Tag | Where it lands | Default I'll use | Override route |
|---|---|---|---|
| **D-141a tier prices** | R-141 + R-150 | Pro=$20/mo, Team=$39/seat/mo, Enterprise=Talk to sales (greenfield §7.1) | Edit doc + restart wave |
| **D-141b currency default** | R-141 + R-150 | USD; INR auto-detected for `Asia/Kolkata` / `en-in` | Same |
| **D-163 nudge timing** | R-163 | Toast at lifetime balance ≤ 1; blocking modal at 0 | Same |
| **D-125 repo-attach gaps** | R-125 | TODO comments in `RepoAttachPopover.tsx` for: (a) private-repo-no-access, (b) mono-repo subdir, (c) repo-too-big, (d) per-run override. Happy path ships; gaps revisited before prod traffic per user request | Same |
| **D-151 SOC2 status copy** | R-151 | "Audit in progress with [Auditor name TBD]" placeholder; user fills in real auditor | Same |
| **D-144 integrations real-vs-soon** | R-144 | Real today: GitHub App + GitHub PAT + Slack + Bitbucket. Schema-only: Jira + Confluence (link to v2). Coming soon: ServiceNow + PagerDuty | Same |

## Wave 1 closeout (recap)

* All 20 Wave-1 R-IDs live on `feature/conversion-redesign`
* `tsc --noEmit` clean
* `mvn test -Dtest='*VerifiedScoreTest,*LifetimeCreditServiceTest,*EntitlementResolverTest'` 23/23 green
* Cohabitation invariant intact (only `EventName.java` + `SecurityConfig.java` got non-additive v2 edits)

## What's not in Wave 2

* **R-171** PWA manifest + service worker — Wave 3
* **R-180** v2 → v3 opt-in redirect map — Wave 3
* **R-181** changelog page + tour banner — Wave 3
* **R-190 / R-191** Vibe deletion + sidebar/Mission vocabulary purge — post-cutover backlog
* **R-111 real LLM dispatch** for anonymous trial — schedule for Wave 2-end if there's bandwidth, else Wave 3 (anti-abuse design pass owed)

## Servers

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/...` | up |
| v2 backend (Spring Boot) | 8080 | proxied via Vite | up (pre-V152 — first R-141 dogfood will trigger restart) |
| v2 backend (alt) | 8081 | direct | up |

## TypeScript / Maven baseline at Wave-2 start

`./node_modules/.bin/tsc --noEmit` clean.
`mvn -q -DskipTests compile` clean.
23/23 backend unit tests across 3 v3 test classes green.
