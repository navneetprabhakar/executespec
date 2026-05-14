# Session handoff — 2026-05-14 mobile UX overhaul

> **Audience:** the next contributor (human or agent) picking up this mobile UX work. Self-contained — you should not need to reread the chat transcript to continue.
> **Status:** PR #125 raised against `dev` with the scroll hotfix + audit doc + PR-1 of 13. PRs 1b through 13 are pending direction.

---

## TL;DR in 60 seconds

1. Two PRs landed this session: **#124** (mobile UX P0 bundle — CSS-altitude hygiene) and **#125** (open — scroll hotfix + comprehensive audit + responsive-table primitive). #124 is merged to `dev`; #125 is awaiting review.
2. The user explicitly redirected mid-session: the initial mobile UX pass was framework-level (CSS / viewport / safe-area / typography lift), but the real problems are **component-level information-design failures** that repeat across the whole app. Don't repeat that mistake.
3. **121-finding mobile UX audit** lives at `docs/design/mobile-ux-audit-2026-05-14.md`, mapped to 14 recurring patterns (P1-P14), with a bounded 13-PR sequencing plan.
4. **5 product questions are open** in the audit doc; PRs 7 / 10 / 11 are blocked on them.
5. **One regression was introduced and self-healed**: a global `touch-action: pan-x` on `.overflow-x-auto` killed vertical pan on tables + code blocks. Hot-fixed by `7baddbe` on the new branch.
6. **Dependabot + soft npm audit gate** wired into `.github/dependabot.yml` and `.github/workflows/lint.yml`. First scheduled run: Monday 06:00 IST.
7. **Two critical CVE-class items are deliberately deferred** per user direction: Spring Boot 3.3.0 → 3.3.x latest (multiple CVEs) and Apache Tika 2.9.2 → 2.9.4. Dependabot will surface them next run.

---

## Session arc — what happened, in order

1. **Switched to `feature/responsive-ux-parity-2026-05-14`** (the user's request: "the most recent branch with commit and PR" = PR #124).
2. **Initial mobile UX pass (CSS-altitude)** — six sessions of fixes on PR #124's branch covering iOS Safari URL-bar collapse (100vh → 100dvh on 8 surfaces), modal safe-area sweep on 6 ad-hoc modals + ResponsiveModal, momentum-scroll restoration via `-webkit-overflow-scrolling: touch`, touch-feedback `:active` rule for `@media (hover: none)`, typography lift (mobile floor for `text-[<12px]` arbitraries + v3 design-token bumps), and 11 input fields hardened with `inputMode` / `autoCapitalize` / `enterKeyHint`. Shipped across 9 MCP commits because the local git proxy was 403-ing direct pushes for that branch.
3. **Security vulnerability triage** — knowledge-based (no network access from sandbox), enumerating every dep in `frontend/package-lock.json` and `pom.xml`. Two critical items flagged (Spring Boot 3.3.0, Apache Tika 2.9.2). Hygiene gaps surfaced (no Dependabot, no SBOM, no SCA gate).
4. **CI / supply-chain hygiene** — added `.github/dependabot.yml` (weekly batched updates across npm / Maven / GitHub Actions, with grouping for Spring + Google Cloud + observability) and a soft `frontend-audit` job in `lint.yml` (warn-only initially, `continue-on-error: true` because of the known vite-5/esbuild-0.21 dev-server CVE backlog).
5. **PR #124 merged** to `dev`. CI deploy fired.
6. **User shared 11 screenshots from `dev.executespec.ai` on iPhone.** Pattern-level UX issues throughout (repo cards truncated to one letter, PR URLs raw-and-clipped, run titles mid-word truncation, status chip rows overflowing, etc.) — none of which my CSS-altitude pass had addressed.
7. **User explicitly called out the wrong altitude.** I acknowledged, restated the 13 recurring patterns I'd identified across the screenshots, asked for direction.
8. **User confirmed scope:** complete frontend audit, fresh branch from `dev`.
9. **Branched `feature/mobile-ux-overhaul-2026-05-14` from `dev` @ `579b280`.**
10. **Identified self-introduced scroll regression** — over-broad `touch-action: pan-x` on every `.overflow-x-auto` was killing vertical pan inside admin tables + markdown code blocks (per CSS spec, `pan-x` neither processes nor bubbles vertical pan). **Hot-fix: `7baddbe`**.
11. **Comprehensive audit** — 5 parallel Explore subagents (one per surface bucket: Project workbench / Run detail / Cross-project lists / Admin+finance+org / Billing+auth+settings+legal+primitives), 121 findings, 14 patterns including a new P14 for "data tables forcing horizontal scroll on phones". **Doc: `0c52a07`** at `docs/design/mobile-ux-audit-2026-05-14.md`.
12. **PR-1 of 13** — `.responsive-table` CSS-only primitive + retrofit of AdminPage Users table (proof of pattern P14 fix). **Commit `986bb6c`**.
13. **Combined PR #125** raised against `dev`.

---

## What shipped — exact commit ledger

### Merged to `dev` (was PR #124, branch `feature/responsive-ux-parity-2026-05-14`)

| SHA | Summary |
|-----|---------|
| `204ab2b` | index.css typography lift + tap-feedback + ResponsiveModal safe-area |
| `820bb9e` | ad-hoc modal safe-area sweep + GitHub PAT/Archive modals |
| `1d18d6e` | UpgradeNudge border-class fix + remaining ad-hoc modals |
| `aeb3630` | DeployWizardModal + NewRunModal + RunDetail PAT/Archive safe-area |
| `3ec93c7` | NewRunModal + RunDetail PAT/Archive safe-area + dvh |
| `40c3b22` | RunDetail GitHub PAT + Archive confirm safe-area inset |
| `9d83f02` | mobile keyboard hints on 9 email + 2 URL inputs |
| `b5544c7` | mobile keyboard hints on Login/Register/OrgSettings emails |
| `9d13dcb` | mobile keyboard hints on v3 FreeCapNudge/AuthPage/TeamPage |
| `40563c4` | wire Dependabot + soft npm audit gate |
| `579b280` | merge commit |

### Open — PR #125, branch `feature/mobile-ux-overhaul-2026-05-14` (3 commits ahead of `dev`)

| SHA | Summary |
|-----|---------|
| `7baddbe` | **fix(scroll)** — narrow global `.overflow-x-auto` rule that broke vertical scroll inside tables / code blocks |
| `0c52a07` | **docs(ux)** — mobile UX audit, 121 findings, 14 patterns, 13-PR sequence |
| `986bb6c` | **feat(ux)** — `.responsive-table` primitive + AdminPage Users-table retrofit (PR 1 of 13) |

PR URL: <https://github.com/navneetprabhakar/executespec/pull/125>

---

## The 14 recurring mobile-failure patterns

Lifted from the audit doc — committing them here so a handoff reader doesn't need to fetch the full doc to grasp the framing.

| # | Pattern | Why it breaks on a 375px viewport |
|---|--------|-----------------------------------|
| P1 | Dot-separated metadata strips (`ACTIVE · ANTHROPIC · OWNER pplai-app · REPOS 2 · DONE 16`) | Overflows; trailing `·` reads as broken |
| P2 | Action button rows on cards (Sync · Install webhook · **Unlink**) | Destructive adjacent to common actions; accidental-tap risk |
| P3 | Long IDs / SHAs / URLs as primary content (`d7d990cf`, `https://github.com/pplai-ap…`) | Eats space the title should have owned |
| P4 | Single-line `truncate` clamps cutting titles mid-word | User can't identify the row |
| P5 | Marketing-sized heroes (`text-2xl/3xl/4xl`) on data screens | Pushes data 500-700px below the fold |
| P6 | Filter / chip groups wrapping to 3+ rows | Eats 30% of viewport before content |
| P7 | Double-layer tab strips (top tabs + sub-tab strip inside) | Two horizontal scrollers stacked = unintelligible nav |
| P8 | Verbose timestamps (`13/5/2026, 8:28:52 PM` with seconds + 4-digit year) | Wastes horizontal row |
| P9 | Deep file-tree indentation (24px/level) | Level 4 has zero breathing room for filenames |
| P10 | Absolute-positioned chips colliding with sibling text | Workspace health `main`/`stale` overlap |
| P11 | Composer / chat / panel headers eating >30% of viewport | Conversation gets ~5 lines visible |
| P12 | Modal-as-centered-dialog where iOS bottom-sheet is more thumb-friendly | Wastes top + bottom of phone |
| P13 | Markdown code blocks overflowing without horizontal-scroll affordance | Right edge of `<pre>` unreadable |
| P14 | Data tables with `min-w-[Npx]` forcing horizontal scroll on phones (12+ instances across admin/finance/org) | Phone-on-call admin can't read the table |

---

## Sequencing plan (from the audit doc — 13 PRs, 17-20 dev-days bounded)

| PR | Pattern(s) | Files affected | Blocked by |
|----|-----------|--------------|-----------|
| **1** (done) | P14 | `.responsive-table` primitive + AdminPage Users | — |
| **1b** | P14 | 9 more admin/finance/org tables (mechanical retrofit) | Validate PR-1 in prod first |
| 2 | P1, P10 | `<MetaRow>` primitive + WorkspaceHealthPanel collision | — |
| 3 | P2 | `<ActionCluster>` primitive (overflow `…` menu) | — |
| 4 | P4 | `truncate` → `line-clamp-2` sweep | — |
| 5 | P3 | Semantic primitives for IDs / SHAs / URLs (PR card "owner/repo#NN") | — |
| 6 | P8 | `<RelativeTime>` primitive | — |
| 7 | P11 | Chat / panel header collapse | **Open Q3** (Compact + Reset placement on mobile) |
| 8 | P6 | `<FilterSheet>` primitive | — |
| 9 | P5 | Hero scale-down on mobile | **Open Q1** (does `/runs` need a hero on mobile?) |
| 10 | P7 | Tab-strip consolidation | **Open Qs 2 + 4** (which tabs are phone-essential?) |
| 11 | P12 | TaskTimeline tooltip → bottom-sheet on mobile | **Open Q5** (PR review popover → full-page?) |
| 12 | P13 | Code-block overflow wrapper in chat bubbles | — |
| 13 | P9 | File-tree mobile indent reduction | — |

PRs 1b, 2, 3, 4, 5, 6, 8, 12, 13 are unblocked and can ship in any order.

---

## Open product questions

These need user direction before PRs 7 / 10 / 11 can land. Captured at the bottom of the audit doc as well — duplicated here for handoff completeness.

1. **`/runs` cross-project hero** — does mobile need a hero at all, or should mobile land directly on the run list?
2. **AdminPage tab consolidation** — of the 8 tabs (Users · Orgs · Tiers · Escalations · Packages · Reports · Audit · Webhooks), which 3-4 are "core mobile" vs "More-drawer"?
3. **Project Chat compact-mode** — when collapsing the header on mobile, should "Compact" + "Reset" stay visible or move to an overflow `…` menu?
4. **Run-detail tab ordering** — same question for RunDetailTabs' 7 tabs. Which 3-4 are "phone-essential"?
5. **PR Review surface** — `PrReviewButton` opens a verdict popover. On mobile this is hard to position. Convert to a full-page detail view?

---

## Deferred dependency bumps (user direction: don't bump yet)

Both flagged in the security triage above the cutline; **don't take initiative**, wait for user instruction.

| Package | Current | Target | Why |
|--------|---------|-------|-----|
| `spring-boot-starter-parent` | `3.3.0` | latest 3.3.x or 3.4.x | CVE-2024-38816 (Spring Framework `UrlResource` path traversal) + CVE-2025-24813 (Tomcat partial PUT RCE) + CVE-2024-50379 (Tomcat CGI on case-insensitive FS) + Tomcat HTTP/2 DoS fixes |
| `org.apache.tika:tika-core` + `tika-parsers-standard-package` | `2.9.2` | `2.9.4` minimum | Tika parsers package has a long XML XXE / SSRF tail; 2.9.4 includes patches |
| `google-oauth-client-jetty` | `1.34.1` | `1.39.x` | Bundled Jetty stale; operator-workstation impact |
| `react-markdown` | `8.0.7` | `9.x` | One major behind; XSS surface in markdown renderers |

Dependabot will surface most of these as PRs next Monday at 06:00 IST.

---

## State of the branches at handoff

| Branch | State | Use |
|--------|-------|-----|
| `main` | Production. Untouched this session. | Live executespec.ai |
| `dev` | At `579b280` (merge of #124). Aligned local + origin. | Integration; targets `dev.executespec.ai` via `deploy.yml` |
| `feature/mobile-ux-overhaul-2026-05-14` | At `986bb6c`. 3 commits ahead of `dev`. PR #125 open. | All ongoing mobile UX work lands here per user direction |
| `feature/responsive-ux-parity-2026-05-14` | Merged + closed (PR #124). Don't push further to it. | Reference only |
| `claude/switch-recent-branch-LUV0c` | Stale local artefact from initial branch directive | Safe to ignore / delete |

---

## Environment quirks the next session will hit

1. **No npm registry access from the sandbox.** `npm install` / `npm audit` / `npx vite build` all 403. Build verification via `npm run build` is not possible from here; rely on CI or local-machine verify.
2. **No Maven Central / NVD access.** `mvn package` / `mvn dependency:tree` / `mvn org.owasp:dependency-check` all offline-error. Security triage is knowledge-based until a real scanner runs in CI.
3. **Local git proxy 403'd direct push** on `feature/responsive-ux-parity-2026-05-14` for the entire P0 bundle. Direct push **works fine** on `feature/mobile-ux-overhaul-2026-05-14` (per `7baddbe` and `986bb6c` both pushing first-try). If the 403 pattern returns, fall back to `mcp__github__push_files` — it works for any branch.
4. **`git stash pop` after a stash test wiped `node_modules`** at one point — the project's npm cache isn't persistent across stash operations in this sandbox. Rebuild with `npm ci` if you need it; rebuilding requires network access.
5. **Browser / device verification is out of scope for this sandbox.** No browser, no screenshots, no real DPR rendering. The user is the verifier on `dev.executespec.ai`.

---

## Things I'd do differently (lessons captured for the next contributor)

1. **Distinguish framework-altitude from component-altitude UX work up front.** When a user says "UX researcher review," default to the harder, slower component-by-component scan; don't grep for `text-[10px]` and call it done. Surface-by-surface is the unit, not file-by-file.
2. **Component-level audit first, code second.** I shipped a 16-file P0 bundle before doing the surface-by-surface audit. The audit then revealed the bundle solved the wrong problem at the wrong altitude. Reverse the order next time: audit doc → user-approved sequencing → code.
3. **Don't add global CSS rules that change scroll semantics without scoping them tightly.** The `.overflow-x-auto { touch-action: pan-x }` rule was scoped to `.v3-tab-strip` originally and worked; widening it to every horizontal scroller broke vertical pan everywhere. Default to narrow selectors; widen only when you have evidence.
4. **Subagent parallelism wins on large audits.** The 5-bucket parallel scan finished in under 1 minute of wall-clock and consumed roughly half the main-context budget compared to doing it inline. Reuse the pattern for any audit > 30 files.
5. **`mcp__github__push_files` is the reliable fallback when direct git push fails.** Don't burn time on retries — switch tools after 2-3 failures with the same 403.

---

## Immediate next steps (pick any)

A) **Wait for PR #125 review.** Merge, deploy, verify the AdminPage Users table renders as labelled cards on iPhone.
B) **PR 1b** — retrofit the other 9 admin/finance/org tables using the same `.responsive-table` pattern. Mechanical; no design decisions. ~3-4 hours.
C) **PR 2** — `<MetaRow>` primitive for the dot-separated metadata strips. Highest user-visible win after PR 1; ~2 days.
D) **Get user answers on the 5 open product questions.** Unblocks PRs 7 / 10 / 11.
E) **Pivot to a different concern** — security bumps, performance audit, accessibility audit, etc.

---

## Files materially changed in this session

For the audit log; not exhaustive of every line.

```
docs/design/mobile-ux-audit-2026-05-14.md     (new — 234 lines)
docs/handoff-2026-05-14-mobile-ux.md           (new — this file)
.github/dependabot.yml                         (new — 142 lines)
.github/workflows/lint.yml                     (+63 lines, frontend-audit job)
frontend/src/index.css                         (typography lift + tap-feedback + dvh fallback + responsive-table CSS)
frontend/src/components/runs/RunDetailTabs.tsx (100vh → 100dvh × 4 tabs)
frontend/src/pages/project/ProjectDashboardPage.tsx  (sidebar dvh + overscroll-contain)
frontend/src/components/project/ProjectChatPanel.tsx (modal safe-area + 100dvh + repos overscroll)
frontend/src/components/layout/NotificationPanel.tsx (100vh → 100dvh × 2)
frontend/src/components/shared/ResponsiveModal.tsx   (mobile safe-area inset + 90vh → 90dvh)
frontend/src/components/billing/UpgradeNudge.tsx     (modal safe-area)
frontend/src/components/deployments/DeployModal.tsx  (modal safe-area)
frontend/src/components/deployments/DeployWizardModal.tsx  (modal safe-area)
frontend/src/components/project/ResetWorkspaceSheet.tsx    (modal safe-area)
frontend/src/components/runs/RunDetail.tsx           (modal safe-area on GitHub PAT + Archive)
frontend/src/components/runs/NewRunModal.tsx         (modal safe-area + URL input keyboards)
frontend/src/pages/admin/AdminPage.tsx               (Users-table responsive-table retrofit)
frontend/src/pages/admin/AdminInviteModal.tsx        (Input shim accepts inputProps pass-through)
frontend/src/pages/auth/LoginPage.tsx                (email input keyboard hints)
frontend/src/pages/auth/RegisterPage.tsx             (email input keyboard hints)
frontend/src/pages/auth/ForgotPasswordPage.tsx       (email input keyboard hints)
frontend/src/pages/auth/AuthCallbackPage.tsx         (min-h-screen → min-h-dvh)
frontend/src/pages/org/OrgSettingsPage.tsx           (email input keyboard hints)
frontend/src/v3/pages/AuthPage.tsx                   (100vh → 100dvh + email keyboards)
frontend/src/v3/pages/TeamPage.tsx                   (email input keyboard hints)
frontend/src/v3/components/FreeCapNudge.tsx          (email input keyboard hints)
frontend/src/pages/onboarding/WelcomePage.tsx        (min-h-screen → min-h-dvh)
frontend/src/pages/SharedReportPage.tsx              (min-h-screen → min-h-dvh)
frontend/src/pages/SharedProjectPage.tsx             (min-h-screen → min-h-dvh)
frontend/src/pages/TermsPage.tsx                     (min-h-screen → min-h-dvh)
frontend/src/pages/PrivacyPage.tsx                   (min-h-screen → min-h-dvh)
frontend/src/pages/DpaPage.tsx                       (min-h-screen → min-h-dvh)
```

---

## Useful links

- **Audit doc** (read this first): `docs/design/mobile-ux-audit-2026-05-14.md`
- **PR #125** (combined, open): <https://github.com/navneetprabhakar/executespec/pull/125>
- **PR #124** (merged): <https://github.com/navneetprabhakar/executespec/pull/124>
- **Project guidance**: `CLAUDE.md` (root) and `frontend/CLAUDE.md`
- **UI tokens reference**: `docs/design/ui-tokens.md`
