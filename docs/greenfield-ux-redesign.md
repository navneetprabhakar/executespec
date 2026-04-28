# ExecuteSpec — Greenfield UX Redesign for Conversion

**Author:** Same fresh-eyes reviewer who wrote the strict competitive UX review on 2026-04-27
**Brief:** Redesign every UX surface from scratch using the same feature set, optimized for conversion.
**Date:** 2026-04-27
**Stance:** Opinionated, not multiple-choice. One direction picked per question, defended in line.

---

## Positioning (the two sentences the entire redesign is built around)

> **ExecuteSpec ships AI-written pull requests you can actually merge — for one repo or for twenty.** *Cursor wrote the code. Devin claimed it works. ExecuteSpec hands you a PR your reviewer can approve in five minutes.*

That's it. Every screen below either advances that promise or earns its place by serving a downstream conversion event (free → paid, pro → team, team → enterprise). Anything that doesn't is cut.

---

## Executive summary

**Counts**

* **Screens redesigned:** 24 (every persona-relevant surface in the existing product, regrouped under a brand-new IA)
* **Top-level destinations:** 3 (was: 17)
* **Ladder rungs visible by default:** 3 (Free · Pro · Team) — Enterprise is a footer link
* **Onboarding clicks to first run:** 2 (was: ~7)
* **Vocabulary words for "run":** 1 (was: 5)

**The one IA decision.** ExecuteSpec is a **project-first chat application**, not a dashboard. The home of the product is a project — a project has a thread of runs, just like a Slack channel has a thread of messages. Every other surface (settings, billing, integrations, team, audit) is reached via `⌘K` palette or the avatar dropdown. **No left sidebar.** The runs list as a top-level destination is deleted.

**The three boldest moves.**

1. **The pull request *is* the report.** No more `/runs/{id}/report` page. When a run completes, the user is dropped into the resulting draft PR view with inline annotations from ExecuteSpec the same shape as a code reviewer's. The "verified score" is replaced by a single pull-request status: **Ready · Build OK · 12/12 tests · Reviewer 0 issues**. If any signal isn't green, the headline says "Needs review" and points at the failing chip. Nothing else.
2. **The home is a textarea.** The signed-in homepage is a single textarea inside the user's most-recent project, with three pre-filled prompt chips and the project's repo context pre-attached. No nav. No sidebar. No runs list. To get to history, scroll the conversation; to get to another project, ⌘K. **This is the v0.dev / Bolt / Lovable pattern, applied to a real-codebase tool.**
3. **The free tier is lifetime, not monthly.** Free = 5 lifetime runs + 5 more for every friend invited (cap 25). The "5 runs/month forever" trap doesn't exist. The conversion bet is that lifetime runs feel generous, monthly resets feel punitive, and referrals become the growth loop. Cursor / Replit / Bolt don't do this — and that's the opening.

**What we're throwing away (call it out so it can't sneak back in).**

* The runs list as a top-level destination
* The "Mission / Launch / Execution" vocabulary
* The "Verified N/100" score badge as a primary signal
* The 8-input new-run modal
* The empty 50%-of-viewport "Select a mission" right pane
* The /accounting page (folds into Billing's tax tab, India-only)
* The /pricing model-rate matrix as a separate page (folds into a `?` drawer)
* The 3-way analytics split (`/dashboard` / `/exec` / `/admin/analytics` → one **Insights** screen)
* The "Vibe View" code path
* The left sidebar
* The breadcrumb (deleted, replaced by project switcher)
* The "Welcome" sample-spec gallery as a separate route (folds into the empty-state of the homepage textarea)
* The plan-approval modal as a blocking gate (becomes inline in the conversation thread)
* The "Production" vs "Prototype" toggle (replaced by per-conversation default with a `?` info chip)
* The `Max` tier (already on the V141 path)
* The "Halt all runs" red header button (moves to a confirm-protected admin destructive-actions panel)

---

## Section 1 — Conversion funnel as it should be

The current funnel (inferred from V125 activation events and the strict-review walk-throughs):

```
Landing (marketing site) → Sign up → Welcome onboarding (sample picker)
  → Runs list (someone else's runs) → New-run modal (8 inputs) → Plan gate
  → Watch run → Validation report → ??? → Free-tier ceiling → Paywall
```

The leaks (in order of visitor loss):

1. **Welcome → first run:** ~70% drop (estimate). Modal complexity + jargon.
2. **First run → second run:** ~50% drop. Score shows 65/100 and the user can't tell why.
3. **Free tier exhausted → paid:** ~85% drop. The Plans page hides the Free tier and the billing page is broken.
4. **Pro → Team:** practically zero today. No team-discovery flow. No "invite a teammate" affordance from inside the product.
5. **Team → Enterprise:** founder-led only. No procurement-grade Trust page.

The new funnel collapses **steps 1-4** into one continuous chat, with the conversion event triggered after value is felt:

```
Landing → "Try without an account" textarea
  → submit (no signup) → see live preview / draft PR
  → "Sign in to save this run" (signup gate AFTER first value)
  → result lives in their inbox forever
  → second run from same convo → upsell to Pro after the 5th lifetime run
  → "Invite a teammate to review this PR with you" (Team upsell on PR view)
  → ENTERPRISE is a footer "Talk to sales" + procurement-grade /trust page
```

Every conversion event is anchored to a *moment of felt value*, not a *moment of friction*. The user pays after they've seen the product work for them, not before.

---

## Section 2 — Information Architecture

### 2.1 The decision: chat-first, project-as-thread, no left sidebar

ExecuteSpec is a **chat app where the assistant ships pull requests instead of words**. The right mental model isn't "dashboard with widgets" or "IDE with panels" — it's **a Slack channel where every reply is a draft PR**.

This drives the IA:

* **Top of every screen:** a thin top bar — `[ExecuteSpec logo · Project switcher ▾]   [⌘K palette]   [Avatar ▾]`. Nothing else. No sidebar. No breadcrumb.
* **Body of the home screen:** the active project's conversation thread, with the textarea pinned to the bottom (Slack/Linear/Discord pattern).
* **Everything else:** ⌘K destinations or avatar-dropdown destinations. Power users live in ⌘K. Casual users never see them.

### 2.2 Why no sidebar

Because **the user does not navigate this product — they converse with it**. A sidebar implies "many places to go". ExecuteSpec's value is "stay in this thread until your code ships." Cursor doesn't have an app sidebar (the editor is the surface). Linear has a sidebar because Linear is a triage tool — but Linear's chat-shaped surface (Lin AI) also dropped the sidebar. v0 is sidebar-less. Bolt is sidebar-less. The pattern wins for chat-first products.

### 2.3 The three top-level destinations

There are three URL roots. That's it.

| Destination | URL | Purpose |
|---|---|---|
| **Home / project thread** | `/p/{slug}` (or `/` → most-recent project) | The conversation. Where 95% of usage happens. |
| **Run detail** | `/p/{slug}/r/{runShort}` | The PR-shaped result of a single run. Reachable via the conversation, by deep link, or from email. |
| **Insights** | `/insights` | The single org-wide view (collapses today's `/dashboard`+`/exec`+`/admin/analytics`). Optional — only shows in nav for ORG_ADMIN+. |

Everything else (Settings, Billing, Integrations, Team, Audit, Templates, Feature flags, Pricing, Trust, Legal) is reached via **⌘K** or the **Avatar dropdown**. Never in the chrome.

### 2.4 The avatar dropdown (the "settings" surface)

```
[Avatar ▾]
  · Account            ⌘,
  · Billing & plan
  · Team               (only if Pro+)
  · Integrations
  · Insights           (only if ORG_ADMIN+)
  · Help               ?
  · Sign out
```

Five primary items, role-gated, in the order people actually need them. **The current product surfaces 12+ sidebar items to every user**; this drops to 5 contextual items per role.

### 2.5 The ⌘K command palette (the power-user surface)

`⌘K` opens a Linear-style command palette with three modes:

* **Default:** fuzzy-match recent runs ("the run yesterday with the OAuth fix")
* **Type `>`:** action mode — `> New project` `> Invite teammate` `> Switch theme` `> Open settings` `> Change tier` `> Halt all runs (admin)` etc.
* **Type `?`:** glossary mode — definitions of every product term

This is where every "obscure but real" feature lives: feature-flag admin, audit log, tier-lifecycle controls, payment audit, GST export, accounting reports. None of them clutter the chrome. Power users find them in 200ms; casual users never see them.

### 2.6 Naming — the entire vocabulary, once

| Concept | Old name(s) | New name | Reasoning |
|---|---|---|---|
| The single act of "AI does work" | Run / Mission / Spec / Launch / Execution | **Run** | URL, API, DB already agree |
| The container | Project / Workspace / Org-Project | **Project** | Universal |
| The result of a successful run | Validation report / Verified score | **Pull request (or "Run result" for non-repo runs)** | The PR is the product |
| The scoring system | Verified score / Accuracy score / Reviewer verdict | **Status** (Ready / Needs review / Failed) + 4 evidence chips | Stop pretending to a single number |
| Plan approval | Plan gate / AWAITING_APPROVAL | **Plan ready — Approve & ship** | Verb the user takes |
| Cost preview | Cost estimate / Hard cap | **About $X** | Plain English |
| LLM provider | Anthropic / ANTHROPIC / anthropic / Anthropic (Claude) | **Anthropic** (one casing forever, never the model name in the chip) | Consistent |
| Tier | Free / Pro / Team / Max / Enterprise | **Free / Pro / Team** + ENTERPRISE in footer only | One ladder, three rungs |
| Sample spec | Sample / Sandbox spec | **Try a prompt** | Verb the user takes |
| Architecture doc | Architecture doc / HLD/LLD | **Code map** | Plain English, defended in /trust copy |
| Activity feed | Activity / Audit | **Activity** (user-visible) · **Audit log** (admin-only via ⌘K) | Two distinct concepts |

This list is loaded into `frontend/src/lib/terminology.ts` and a lint rule fails the build on any old name in user-facing strings.

---

## Section 3 — Visual design language

### 3.1 Direction (one direction, defended)

**Linear-modern + Stripe-honest.** Tight type scale (12/14/16/22/32px), generous line-height (1.55), single brand accent, semantic colour palette, off-black background (`#0B0D10` already exists), no gradients on chrome. Quietly opinionated, never garish.

Why this and not v0 / Vercel / Bolt:

* **v0's playful direction** is wrong for a tool that touches your production codebase.
* **Vercel's brutalist direction** is wrong for a tool that wants enterprise procurement to approve it.
* **Bolt's playful direction** is wrong for the same reason as v0.
* **Linear sets the standard** for "serious software for serious people, but not boring." Cursor borrowed it. Stripe set the same tone before Linear. ExecuteSpec inherits a 12-year industry pattern.

### 3.2 Type

* **Body / UI:** Geist (already in the `monacoSetup.ts` theme name). Falls back to system-ui.
* **Mono:** Geist Mono. Falls back to ui-monospace.
* **Sizes:** 11.5 (caption) · 13 (body) · 14 (UI) · 16 (lead) · 22 (h2) · 32 (h1). Never larger; never smaller in the app.
* **Weight:** 400 / 500 / 600. Never 700 except in landing copy.

### 3.3 Color

```
Background       #0B0D10 (off-black)
Surface          #11151B
Surface-elevated #161B22
Border           #1F242C
Border-strong    #2A2F37
Text             #E8EAED
Text-muted       #94A3B8
Text-subtle      #5E6771

Brand accent     #5B8DEF (Signal Blue, already in v2.6.0)
Brand secondary  #7AD1C8 (Mint, already in v2.6.0)

Success          #10B981
Warning          #F59E0B  (PARTIAL chip uses this; never share with brand)
Error            #EF4444
Info             #5B8DEF (= brand)

Light mode: invert with same hue ratios. Default to dark; remember pref.
```

The brand accent and the warning hue must never share a hue family. The strict review caught the orange/orange clash; this palette resolves it.

### 3.4 Spacing

8-point system. 4 / 8 / 12 / 16 / 24 / 32 / 48 / 64. Component padding always one of these values.

### 3.5 Density

**Tight, like Linear.** Row heights 32-44px in lists, 56px hero rows, 12px inter-card gap. Not Notion-roomy. The user is doing work, not browsing.

### 3.6 Motion

* 120ms ease-out on hover state changes
* 180ms ease-in-out on layout shifts (modal open, drawer slide)
* No bouncing, no springs, no parallax
* SSE-driven content fades in over 200ms; spinners only above 800ms wait

### 3.7 The component library (from the existing `frontend/src/components/shared/`)

Reuse: `Button` (cva), `Checkbox`, `Radio`, `ResponsiveModal`, `Tooltip`, `StatusBadge`, `SkeletonLoader`, `Toast`, `MarkdownContent`, `EmptyState`.

Add (small):

* `<Term name="run">Run</Term>` — glossary tooltip wrapper
* `<Pill tone="ok|warn|err|neutral|info">label</Pill>` — single status primitive (replaces 5 different chip implementations)
* `<DiffViewer />` — wraps Monaco diff editor for inline PR diffs
* `<ChatBubble role="user|assistant">...</ChatBubble>` — for the new conversation thread
* `<CmdK />` — the command palette
* `<ProjectSwitcher />` — the top-bar dropdown
* `<RunStatusHeader />` — the unified PR-shaped status block

Drop: `Sidebar`, `Header` (existing dynamic-breadcrumb header — replaced by `TopBar`), every Vibe component.

---

## Section 4 — Page-by-page redesign

> Format per page: **Goal · Layout · Copy · Interaction · Hidden / progressive disclosure · Conversion event**. Verbatim copy is in italic-quoted blocks.

### 4.1 Marketing landing — `/` (logged-out)

**Goal.** Free user clicks "Try" within 8 seconds. Conversion event: textarea submit (no signup required for first run; ungated trial).

**Layout (above the fold, full-bleed):**

```
[ExecuteSpec wordmark · top-left]                        [Sign in · top-right]

                       Ship AI pull requests
                       you can actually merge.

                Cursor writes code. Devin claims it works.
                ExecuteSpec hands you a PR your reviewer
                can approve in five minutes.

         ┌──────────────────────────────────────────────────────┐
         │ Describe what you want to build or change…           │
         │                                                      │
         │ e.g. "Add a forgot-password flow to my Spring app"   │
         │                                                      │
         └──────────────────────────────────────────────────────┘
              [+ Attach a GitHub repo]   [▾ Pick example]
                              [Submit — free, no signup]

                  • 5 free runs · no credit card · 60-second trial
```

Below the fold: 3 logos of products built ("trusted by"), a 30-second demo loop (silent, autoplay, looping diff-being-written-then-PR-being-opened), 3 social-proof testimonials (one each: solo dev, staff eng, EM), pricing-card preview, footer.

**Copy.**

* Hero H1: **Ship AI pull requests you can actually merge.**
* Hero subhead: *Cursor writes code. Devin claims it works. ExecuteSpec hands you a PR your reviewer can approve in five minutes.*
* CTA: **Submit — free, no signup**
* Below CTA: *5 free runs · no credit card · 60-second trial*
* Sub-CTA chips: *"Add OAuth to my Next.js app"* · *"Refactor a React component to TypeScript"* · *"Build a Stripe checkout endpoint"* (these are the real spec samples that already work)
* Social proof: *"It's the first AI coding tool that I've actually let near `main`." — staff eng, fintech*

**Interaction.**

* Submit (no signup) → backend creates an anonymous "guest project" tied to a session cookie. Run executes with provider preset (Anthropic for code quality). Live preview in 60s.
* On result page: "Want to keep this run? **Save your work — sign in with GitHub**." (single OAuth button, no form). Result is then in the user's inbox.
* "Pick example" → drops a sample into the textarea (no extra page navigation).

**Hidden / progressive disclosure.**

* Pricing reveal on scroll (lazy)
* Enterprise content on a separate `/trust` route (footer link)
* "How it works" content on the same scroll (no separate `/about` page)

**Conversion event.** `landing_textarea_submit` fires on submit. Tracked in PostHog. Funnel target: 35% of unique visitors submit.

**Reference.** Bolt.new's homepage. v0.dev's homepage. Both are textarea-first, no signup-first, no marketing-content-first. Cursor's homepage has marketing first then a download link — that's because Cursor is an IDE; ExecuteSpec is web-native and can convert in-page.

---

### 4.2 Signup / auth — `/auth` (only after first-run value)

**Goal.** Save the user's free run into a real account so they don't lose it. Conversion event: `signup_completed`.

**Layout.**

Single centered card:

```
                         Save this run.

         You just shipped a real PR — sign in to keep it.

                   ┌───────────────────────────┐
                   │  Continue with GitHub     │
                   └───────────────────────────┘
                   ┌───────────────────────────┐
                   │  Continue with Google     │
                   └───────────────────────────┘
                              ─ or ─
                       email + magic link
```

**Copy.**

* H1: **Save this run.**
* Subhead: *You just shipped a real PR — sign in to keep it.*
* No "Welcome back". No "Sign up free". The framing is "save what you already did."

**Interaction.**

* OAuth (preferred): GitHub or Google. After auth, the anonymous run is bound to the user account. Drop into `/p/{auto-generated-slug}/r/{runShort}` — straight back into the result.
* Magic-link fallback: enter email, get a link, click, in. No password creation step. Password is set later from Account settings if the user wants one.

**Hidden / progressive disclosure.** No password field on the signup screen. No phone number. No company. No role. We learn those over time from behaviour, not from a form.

**Conversion event.** `signup_completed` (already V125-tracked).

**Reference.** Linear's signup is OAuth-first, magic-link-second, no password. Notion is the same. Cursor's is download-first, account-after.

---

### 4.3 Empty home — `/` (signed-in, no projects yet)

**Goal.** Get the new user from "I just signed in" to "I just ran a spec" in one click. Conversion event: `first_run_started`.

**Layout.**

```
        [TopBar: ExecuteSpec · No project yet · ⌘K · Avatar]

                   What do you want to build?

         ┌────────────────────────────────────────────┐
         │ Describe it in plain English…              │
         └────────────────────────────────────────────┘

      [+ Connect a GitHub repo]   [▾ Try an example]   [Submit]

   ─────────────────────────────────────────────────────────

            New here? Try one of these — takes 60 seconds:

   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
   │ Word-freq    │ │ Contact API  │ │ Landing page │
   │ Python CLI   │ │ Spring Boot  │ │ React + Vite │
   │ ⏱ 1m · ~$0.05│ │ ⏱ 2m · ~$0.20│ │ ⏱ 2m · ~$0.18│
   │ Try this →   │ │ Try this →   │ │ Try this →   │
   └──────────────┘ └──────────────┘ └──────────────┘

           [skip the samples — paste your own spec]
```

**Copy.**

* H1: *What do you want to build?*
* Hint under chips: *Takes 60 seconds. We'll show you a draft PR you can copy or merge.*

**Interaction.**

* Click sample → textarea pre-fills, run starts immediately on submit.
* Click "Connect a GitHub repo" → OAuth scope grant for repo selection in a popover (no separate Settings page navigation).
* Submit → conversation starts in a new project named after the spec (auto-named: "Word-frequency CLI · just now").

**Hidden / progressive disclosure.** Provider, mode (Plan & review vs Quick run), branch, batch flag, TDD, JIRA — all gone from this screen. Defaults are smart: provider = Anthropic for code quality (overridable in Settings), mode = "Plan & review" (we surface the plan inline in the chat, not in a modal).

**Conversion event.** `first_run_started`.

**Reference.** Notion's "What do you want to do today?" empty state. Lovable's prompt-first homepage. Linear's "Create your first issue" empty state.

---

### 4.4 Project home (the dominant screen) — `/p/{slug}`

**Goal.** Returning users live here. Submit a follow-up run, read previous runs, hand off context. Conversion event: `repeat_run_submitted`.

**Layout.** Slack/Linear chat shape. Top bar pinned. Conversation scrolls. Composer pinned at bottom.

```
[TopBar: ExecuteSpec · Hostel Eval ANTHROPIC ▾ · ⌘K · Avatar ▾]
─────────────────────────────────────────────────────────────────

  ▸ Project pulse  ✓ Build OK · 2 repos · 7 open PRs · 17h ago
                                                          [▾ details]

  ─────────  Tuesday, April 15  ─────────────────────────

  [ASSISTANT, 17:42]   Run #c2e83249 · Status: Ready · Build OK · 73/73 tests
  ┌───────────────────────────────────────────────────────────┐
  │ Built: Spring Boot 3.3 Library Management REST API       │
  │ ✓ Build OK · ✓ 73/73 tests · ✓ Reviewer · Verified 92    │
  │                                                          │
  │ [Open PR #28]   [View diff]   [Re-run]   [Share]         │
  └───────────────────────────────────────────────────────────┘

  [USER, 17:50]
  > Add JWT authentication to the existing /api/books endpoints

  [ASSISTANT, 17:50]   Plan ready — Approve & ship
  ┌───────────────────────────────────────────────────────────┐
  │ I'll modify 4 files in hostel-be-v3:                     │
  │   src/main/java/.../SecurityConfig.java                  │
  │   src/main/java/.../JwtFilter.java                       │
  │   src/main/java/.../AuthController.java                  │
  │   src/test/java/.../AuthControllerTest.java              │
  │                                                          │
  │ About $0.40 · ~3 min                                     │
  │                                                          │
  │ [Approve & ship]   [Adjust plan]   [Cancel]              │
  └───────────────────────────────────────────────────────────┘

  ─────────────────────────────────────────────────────────

  ┌───────────────────────────────────────────────────────────┐
  │ Reply or describe a follow-up change…                    │
  │                                                          │
  └───────────────────────────────────────────────────────────┘
   [Attach repo: hostel-be-v3 ▾]   [Quick run · skip plan]
                                              [Submit]
```

**Copy.**

* TopBar project switcher: **Hostel Eval ANTHROPIC ▾** (or "+ New project" inline)
* Pulse summary (one row): **Project pulse · ✓ Build OK · 2 repos · 7 open PRs · 17h ago [▾ details]**
* Result card title format: *Built: {short title}*
* Plan card title: *Plan ready — Approve & ship*
* Composer placeholder: *Reply or describe a follow-up change…*

**Interaction.**

* Conversation is the source of truth. Each `[ASSISTANT, N]` message is either:
  * **Plan card** → user clicks Approve & ship in-line (no modal). The card transitions to "Building…" with a per-task progress strip.
  * **Result card** → links to the run detail (the PR-shaped surface).
  * **Status update** → Build OK, Tests passing, Build failed, etc. (system messages).
* Follow-up runs accumulate inline. Conversation history is the run history; no separate "runs list" page.
* Click project name → switcher dropdown lists all projects + "+ New project". Same dropdown is what ⌘K opens.
* `[▾ details]` on the pulse row → expands the pulse card inline (last run hero, score, repos, open PRs, recent activity — same content as today's Pulse tab but expanded inline rather than on a tab).

**Hidden / progressive disclosure.**

* Code map / Architecture doc → opened from `[▾ details]` → "Open code map →" link
* Memory → ⌘K → "Show project memory"
* Templates → composer's `[▾]` → "Save this spec as a template"
* Tech debt panel → ⌘K → "Show tech debt"
* Workspace health → top-row Pulse expansion
* Project documents (RAG) → drag-and-drop into composer
* Activity feed → ⌘K → "Show activity"
* Standards / contracts / ADRs / invariants (E1-E4 enterprise readiness) → ⌘K → "Show contracts" / "Show standards" / etc.
* Project export → ⌘K → "Export project as zip"

Every existing feature is reachable. None of them clutter the chat.

**Conversion event.** `repeat_run_submitted` is the headline metric for this screen. Funnel target: 60% of users with ≥1 run submit a second run within 24 hours.

**Reference.** Slack's channel + thread shape. Linear's project view (with chat-like activity feed). Cursor's agent panel (the conversation history below the input box). Devin's session view but cleaner.

---

### 4.5 Run detail (the PR-shaped result) — `/p/{slug}/r/{runShort}`

**Goal.** User can decide "merge / re-run / re-prompt" in <30 seconds. Conversion event: `pr_merged_via_executespec`.

**Layout.** Three-zone layout: status header, file diff, action rail.

```
[TopBar same as project view, breadcrumb-suffix: " · Run c2e83249"]
─────────────────────────────────────────────────────────────────

 Library Management REST API · run c2e83249 · 1d ago

 ✓ Ready to merge      [Open PR on GitHub]   [Re-run]   [Re-prompt]
   ─────────────────────────────────────────────────────────
   Build OK · 73/73 tests · Reviewer 0 issues · Anthropic · ~$1.74 · 11m50s
   ─────────────────────────────────────────────────────────

 ┌──────────────────────────────┬────────────────────────────────┐
 │ Files changed (12)           │ src/main/java/.../BookController.java
 │ ┌──────────────────────────┐ │
 │ │ ▾ src/main/java/.../     │ │ @RestController
 │ │   ▾ BookController.java  │ │ @RequestMapping("/api/books")
 │ │       +47 / -3           │ │ public class BookController {
 │ │   ▾ BookService.java     │ │
 │ │       +28 / -0           │ │   @Autowired
 │ │   ▾ Book.java            │ │   private BookService service;
 │ │       +12 / -0           │ │
 │ │ ▾ src/test/java/.../     │ │ + @GetMapping
 │ │   ▾ BookControllerTest...│ │ + public List<Book> list() {
 │ │       +73 / -0           │ │ +   return service.findAll();
 │ │ ...                      │ │ + }
 │ └──────────────────────────┘ │
 │                              │ [Comments (3) ▾]
 │ [Show only changes]          │  ─────────────
 │                              │  ExecuteSpec, 1d ago
 │                              │  Added pagination defaults to keep
 │                              │  the response small for large libraries.
 └──────────────────────────────┴────────────────────────────────┘
```

**Copy.**

* Status hero: **✓ Ready to merge** (when all signals green); **⚠ Needs review** (when any signal warns); **✗ Build failed** (when build failed).
* Subline (always present): one row of evidence chips, plain English, no emoji theatre.
* Inline annotations: written in first person ("ExecuteSpec, 1d ago: Added pagination defaults…")

**Interaction.**

* Click file in tree → diff loads in right pane (Monaco diff).
* Click `[Open PR on GitHub]` → opens the GitHub PR (when repo run); for non-repo runs the button is `[Open preview]` (Cloud Run URL).
* Click `[Re-run]` → opens composer pre-filled with the same spec.
* Click `[Re-prompt]` → opens composer pre-filled with "Adjust this run: …".
* Click `[Share]` (in `…`) → generates a shared-report link (existing `shareValidationReport` API).

**Hidden / progressive disclosure.**

* Plan structure (per-task list) → `[▾ How it was built]` collapsible below the file tree
* Engine telemetry (per-phase costs, classifier reasoning, plan strategy) → `[▾ Engine details]` further below — power-user surface, never first-paint
* Logs → ⌘K → "Show run logs"
* Test report → `[▾ Test report]` if tests were generated
* Conversation thread that produced this run → "← Back to project" returns to `/p/{slug}` with this run's bubble auto-scrolled into view

**Conversion event.** `pr_merged_via_executespec` (webhook on merge). Funnel target: 50% of completed runs result in a merge or re-run within 7 days.

**Reference.** GitHub PR view, exactly. Diff in the middle, files on the left, comments inline. Cursor's diff review for individual changes. Devin's run summary — but Devin's is too talky; ours is PR-shaped.

---

### 4.6 Run failure / partial — same URL, different state

**Goal.** Fail honestly, point at the next action. Conversion event: `failed_run_re_prompted` (recovery, not abandonment).

**Layout differences from 4.5:**

```
 ⚠ Needs review                  [Re-prompt]   [Open in editor]   [Cancel]
   ─────────────────────────────────────────────────────────
   ✓ Build OK · ✗ 12/15 tests · ⚠ Reviewer (1 issue) · ~$1.20 · 8m
   ─────────────────────────────────────────────────────────

 What ExecuteSpec found:
 ┌──────────────────────────────────────────────────────────────┐
 │ ⚠ The reviewer flagged: BookService is missing transactional│
 │   boundaries on the bulk-update path. Suggested fix below.  │
 │                                                              │
 │ ✗ 3 tests failed: BookControllerTest.testBulkUpdateRollback │
 │   asserts `updateCount == 0` after exception, but the       │
 │   service's @Transactional is missing. See line 47.         │
 │                                                              │
 │ [Apply suggested fix]   [Re-prompt with this context]       │
 └──────────────────────────────────────────────────────────────┘

 Files changed · diff view · comments — same as success state below.
```

**Copy.**

* Header: **⚠ Needs review** (never "PARTIAL", never "Blocked")
* Sub-summary: short, evidence-based, names the failing chip first
* "What ExecuteSpec found:" copy is verbatim, first-person, points at the file/line
* CTA: **Apply suggested fix** (one-click; runs a targeted patch run that costs less)

**Interaction.**

* `[Apply suggested fix]` → backend dispatches a targeted patch run (existing `taskRetry` endpoint) without leaving this page.
* `[Re-prompt with this context]` → composer pre-filled with "The previous run failed because: <error tail>. Please fix it."
* No "Verified score". No "Shippable". No false confidence chip.

**Hidden / progressive disclosure.** Same as success state.

**Conversion event.** `failed_run_re_prompted` — recovery is the metric. Funnel target: 70% of failed runs receive a re-prompt within 1 hour (vs abandonment).

**Reference.** Cursor agent's "I tried X and got: <err>" narration. GitHub Actions step output preview.

---

### 4.7 Project switcher / new project — popover from TopBar

**Goal.** Switch projects in <2 seconds. Create a new project in <5. Conversion event: `project_created`.

**Layout.**

```
[Hostel Eval ANTHROPIC ▾]
 ┌─────────────────────────────────────────┐
 │ ⌕ Search projects…                      │
 │ ─────────────────────────────────────── │
 │ ✓ Hostel Eval ANTHROPIC      2 repos    │
 │   Hostel Eval OPENAI         2 repos    │
 │   Hostel Eval GEMINI         2 repos    │
 │ ─────────────────────────────────────── │
 │ + New project                           │
 │ + Connect a GitHub repo                 │
 └─────────────────────────────────────────┘
```

**Interaction.**

* Click project → switch (URL updates, conversation loads)
* "New project" → inline form (no separate page): name + repo selector. Submit → drop into the new project's empty state (4.4 with no messages).

**Hidden.** Project settings (members, branch defaults, AI provider preference) live inside the project's `[▾] settings` link in the pulse-row dropdown — never a top-level destination.

**Conversion event.** `project_created`.

**Reference.** Linear's project switcher. Slack's workspace switcher.

---

### 4.8 Settings — `/settings` (avatar dropdown)

**Goal.** User configures their account in <60 seconds. Conversion event: `settings_completed_first_time`.

**Layout.** Single-page-app stack of cards (no sub-routes). Top to bottom:

```
[TopBar same]
─────────────────────────────────────────────────────────────────

  Account
  ┌─────────────────────────────────────────────────────────┐
  │ Display name:  Super Admin           [Edit]             │
  │ Email:         admin@executespec.ai  [Change]           │
  │ Password:      Set via OAuth         [Set password]     │
  │ Theme:         Dark · Light · System                    │
  └─────────────────────────────────────────────────────────┘

  AI provider
  ┌─────────────────────────────────────────────────────────┐
  │ Default model: Anthropic Claude Sonnet 4.6              │
  │   Use my own API keys (BYOK)         [Add keys]         │
  └─────────────────────────────────────────────────────────┘

  Repos
  ┌─────────────────────────────────────────────────────────┐
  │ GitHub:        Connected (org navneetprabhakar)         │
  │   Personal access token                  [Manage]       │
  │   Install GitHub App for org             [Install]      │
  └─────────────────────────────────────────────────────────┘

  Team       (Pro+ only)
  Notifications
  Danger zone   (delete account, sign out everywhere)
```

**Copy.** Direct, no headers like "Configure your workspace" or "Account & preferences". Card titles do the work.

**Interaction.** All inline editing. No deep nav. No sub-routes.

**Hidden / progressive disclosure.** No "Vibe View" toggle (deleted). No "JIRA / Confluence / ServiceNow" — these live under `Integrations`. No "Tier" — that lives under `Billing`.

**Conversion event.** `account_completed_first_time` (display name set + theme picked).

**Reference.** Linear's Settings (single-page stack of cards, no nested routes). Vercel's Account page.

---

### 4.9 Billing & plan — `/billing` (avatar dropdown)

**Goal.** User upgrades from Free → Pro or adds seats. Conversion event: `tier_upgraded`.

**Layout.** Three sections, top to bottom:

```
  Plan
  ┌─────────────────────────────────────────────────────────┐
  │  You're on Free.                                        │
  │  3 of 5 lifetime runs used · invite friends for more →  │
  │                                                         │
  │  [Upgrade to Pro — $20/mo]                              │
  └─────────────────────────────────────────────────────────┘

  Usage this month
  ┌─────────────────────────────────────────────────────────┐
  │  Runs:    3                                             │
  │  Spend:   $0.84   (≈ $0.28 per run avg)                 │
  │  Top-spend run: "Library Management REST API" — $1.74   │
  └─────────────────────────────────────────────────────────┘

  Plans                                       [Annual · Monthly]
  ┌──────────────┬──────────────┬──────────────┐
  │ Free         │ Pro          │ Team         │
  │ Current ✓    │ Most popular │ For 2-20     │
  │              │              │              │
  │ $0           │ $20/mo       │ $39/seat/mo  │
  │              │              │              │
  │ 5 lifetime   │ Unlimited    │ Unlimited    │
  │ +5/invite    │ runs         │ runs         │
  │ ≤cap 25      │              │ shared pool  │
  │              │              │              │
  │ 1 project    │ Unlimited    │ Unlimited    │
  │ 1 repo       │ projects     │ projects     │
  │              │ + repos      │ + repos      │
  │              │              │              │
  │              │ BYOK option  │ SSO + audit  │
  │              │              │              │
  │ [Current]    │ [Upgrade]    │ [Add seats]  │
  └──────────────┴──────────────┴──────────────┘

  Need more? Talk to sales for Enterprise (SSO + SOC2 + DPA).

  ── Receipts and tax ────────────────────────────────────────
  Past invoices · Billing profile · GST (India only)
```

**Copy.**

* Plan headline always echoes the actual tier ("You're on Free." / "You're on Pro." / "You're on Team — 4 of 6 seats used.")
* Usage shows real numbers — no "X of Y" if Y is -1 (unlimited)
* Pricing card: simple, three columns, current tier badged "Current"
* Enterprise gets a single line at the bottom — not a fourth card

**Interaction.**

* "Upgrade to Pro" → in-page Stripe checkout (no redirect to a separate page when possible).
* "Add seats" → inline seat-picker + Stripe checkout.
* Receipts/billing-profile/GST collapse below by default. GST only renders if `org.country === 'IN'`.

**Hidden / progressive disclosure.**

* Yearly toggle exposed inline (`Annual · Monthly` toggle above the cards)
* GST is hidden for non-India users (geo-gated)
* The "Top-up credits" path (per-run credits) is gone — Pro is unlimited, so there's no need
* The 11-row plan catalog is collapsed into a clean 3-tier view; backend retains all 11 rows for backward compat

**Conversion event.** `tier_upgraded`. Funnel target: 25% of users at the lifetime free-run cap upgrade within 7 days.

**Reference.** Linear's pricing page. Stripe's pricing page. Cursor's $20 Pro card. The simplicity of "3 cards, the middle one is highlighted" is the global default for SaaS pricing for a reason.

---

### 4.10 Team — `/team` (avatar dropdown, Pro+ only)

**Goal.** Pro user invites first teammate. Team admin manages seats. Conversion event: `team_seat_added`.

**Layout.**

```
  Team — Hostel Eval Org   ·   4 of 6 seats used   [Add seats]

  ┌─────────────────────────────────────────────────────────┐
  │ Member            Role        Last active   Actions     │
  ├─────────────────────────────────────────────────────────┤
  │ Super Admin       Admin       just now      —           │
  │ navneet@…         Developer   1h ago        [▾]         │
  │ adva-test@…       Developer   2d ago        [▾]         │
  │ ── empty seat ──                                        │
  │ ── empty seat ──                                        │
  └─────────────────────────────────────────────────────────┘

  + Invite by email …                            [Send]

  Pending invitations (1)
  ┌─────────────────────────────────────────────────────────┐
  │ priya@example.com   sent 2d ago   [Resend] [Revoke]     │
  └─────────────────────────────────────────────────────────┘

  Roles & permissions  ▾
```

**Copy.** No-jargon. "Member · Role · Last active · Actions" — boring on purpose.

**Interaction.** Add seats inline. Invite by email inline. Roles dropdown inline.

**Hidden.** Audit log is `Team → ▾ Roles & permissions → Show audit log` (avoids cluttering primary surface). SSO config is at the top of the page when ENTERPRISE.

**Conversion event.** `team_seat_added` for Team upsell; `teammate_first_run` for activation depth.

**Reference.** Linear's Members page. GitHub's Teams page.

---

### 4.11 Insights — `/insights` (avatar dropdown, ORG_ADMIN+ only)

**Goal.** Org admin sees what the team shipped this week. Conversion event: `insights_viewed_weekly` (retention metric, not conversion).

**Layout.** Single-page, top to bottom (collapses today's `/dashboard` + `/exec` + `/admin/analytics`):

```
  This week — 3 projects · 28 runs · $27.47 spend · 80% success rate

  ┌─────────────────────────────────────────────────────────┐
  │ Spend by project                       [Last 30 days ▾] │
  │ ▓▓▓▓▓▓▓▓▓▓▓ Hostel Eval OPENAI    4 runs    $7.85      │
  │ ▓▓▓ Hostel Eval ANTHROPIC          2 runs    $1.81      │
  │ ▓▓▓ Hostel Eval GEMINI             2 runs    $1.69      │
  └─────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────┐
  │ Top wins this week                                      │
  │ • Library Management API — Pro user · 4d ago · merged   │
  │ • Spring Boot REST API — Pro user · 5d ago · merged     │
  │ • Library Management — Pro user · 5d ago · merged       │
  └─────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────┐
  │ Needs attention                                         │
  │  All clear in the last 7 days.                          │
  └─────────────────────────────────────────────────────────┘

  Activity feed ▾
  Audit log ▾  (admin only)
```

**Copy.** Plain English, leading with the action ("Library Management API — Pro user · 4d ago · merged"). No "Top 3 wins" without saying what was won.

**Conversion event.** `tier_upgraded` from a "Spend > 80% of plan" nudge in the spend card.

**Reference.** Linear's Insights. Stripe's dashboard summary card.

---

### 4.12 Integrations — `/integrations` (avatar dropdown)

**Goal.** Power user wires Slack / GitHub App / Jira in <90 seconds each. Conversion event: `integration_connected`.

**Layout.** A grid of cards, ordered by "most-installed" first.

```
  Integrations

  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
  │ GitHub App   │ │ GitHub PAT   │ │ Slack        │
  │ Org-wide ✓   │ │ Personal     │ │ Connect →    │
  │ [Manage]     │ │ [Manage]     │ │              │
  └──────────────┘ └──────────────┘ └──────────────┘

  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
  │ Bitbucket    │ │ Jira         │ │ Confluence   │
  │ Connect →    │ │ Connect →    │ │ Connect →    │
  └──────────────┘ └──────────────┘ └──────────────┘

  ┌──────────────┐ ┌──────────────┐
  │ ServiceNow   │ │ PagerDuty    │
  │ Coming soon  │ │ Coming soon  │
  └──────────────┘ └──────────────┘
```

**Copy.** Card title = integration name. State chip = "✓ Connected" / "Connect →" / "Coming soon". No paragraph copy on the card; click to learn more.

**Interaction.** Connect → in-page OAuth or token form. No nested settings page per integration.

**Conversion event.** `integration_connected`.

**Reference.** Vercel's integrations marketplace. Linear's integrations page.

---

### 4.13 Trust & Security — `/trust` (footer link, public, no auth)

**Goal.** Amara/procurement reads this in 5 minutes and approves the vendor. Conversion event: `trust_page_pdf_downloaded`.

**Layout.** Long-scroll, sectioned:

```
  Trust at ExecuteSpec

  • SOC 2 Type II — Audit in progress with [Auditor]
  • Data residency — All compute in us-east4 (Northern Virginia)
  • Encryption — TLS 1.3 in transit, AES-256 at rest
  • Sub-processors — OpenAI · Anthropic · Google AI · GCP · Stripe · Razorpay
  • Authentication — OAuth (Google · GitHub) · SAML (Enterprise) · OIDC
  • Audit log — exportable JSON · 7-year retention (Enterprise)
  • Data export — one-click project export
  • Data deletion — 30-day account deletion SLA
  • IP / model output ownership — yours
  • Code retention — workspaces destroyed 24h after last access
  • DPA & sub-processor list — downloadable PDF
  • Contact — security@executespec.ai

  [Download Trust Pack PDF (4 pages)]
```

**Copy.** Bullet-dense. No marketing fluff. Each row is testable.

**Interaction.** Single PDF download. SAML config is reachable via Trust → "Set up SSO" → `/team` → SSO panel.

**Conversion event.** `trust_page_pdf_downloaded` — predicts enterprise sales close.

**Reference.** Linear's `/security`. Stripe's `/privacy`. sourcegraph.com's trust page.

---

### 4.14 Pricing — `/pricing` (public marketing route)

**Goal.** Anonymous visitor decides on a tier. Conversion event: `pricing_cta_clicked`.

**Layout.** Same 3-card grid as `/billing`'s plans section, but with full marketing copy:

```
  Simple, transparent pricing.
  Your runs are real PRs — pay for what works.

  [Annual · Monthly · USD · INR]

  ┌──────────────┬──────────────┬──────────────┐
  │ Free         │ Pro          │ Team         │
  │              │ Most popular │              │
  │ $0           │ $20 / month  │ $39/seat/mo  │
  │ ─────────    │ ─────────    │ ─────────    │
  │ 5 lifetime   │ Unlimited    │ Unlimited    │
  │ runs         │ runs         │ runs (shared)│
  │              │              │              │
  │ + 5/friend   │ All providers│ Org SSO      │
  │ (cap 25)     │              │ Audit export │
  │              │ BYOK option  │ Activity feed│
  │              │              │              │
  │ Try free →   │ Get started→ │ Talk to us → │
  └──────────────┴──────────────┴──────────────┘

  Need SOC2, DPA, dedicated infra? → Talk about Enterprise

  ── Frequently asked questions ──
  • What counts as a "run"?  ▾
  • Can I bring my own API keys?  ▾
  • What happens to my code?  ▾
  • Do you train on my code?  ▾
  • What's the cancellation policy?  ▾
```

**Copy.** Single line per tier; let the comparison row do the work. Currency + period toggles inline. "Most popular" badge on Pro (the conversion target).

**Hidden.** Per-model token rates → "How is run cost calculated?" expander → opens a small drawer with the rate matrix (today's `/pricing` page collapses into here).

**Conversion event.** `pricing_cta_clicked` (per-tier).

**Reference.** Linear's pricing. Cursor's pricing. Stripe's pricing. All three converge on this layout for a reason.

---

### 4.15 Help / glossary — ⌘K → `?` mode (avatar dropdown → Help)

**Goal.** User self-serves an answer in <30 seconds. Conversion event: `help_query_answered_without_support`.

**Layout.** A modal with three tabs: Glossary · Shortcuts · Docs.

```
  [⌘K → ? mode]

  ⌕ Search help…

  Glossary | Shortcuts | Docs

  · Run            "A single submit-to-result cycle. ExecuteSpec…"
  · Project        "A collection of related runs and repos."
  · Plan           "What ExecuteSpec proposes to change before…"
  · Verdict        "Build OK / Tests pass / Reviewer pass — three…"
  · Code map       "ExecuteSpec's understanding of your repo…"
  · BYOK           "Bring Your Own Keys — use your own API quotas…"
```

**Copy.** Two-sentence definitions per term. Linkable.

**Conversion event.** `help_query_answered_without_support`.

**Reference.** Linear's `?` overlay. Notion's help drawer.

---

### 4.16 Audit log — ⌘K destination, admin only

**Goal.** Compliance pulls a forensic record. Conversion event: enterprise renewal (lagged metric).

**Layout.** Today's `/audit` page is good — keep the FROM/TO date pickers, entity-type chips, time-stamped table. **Add field-level diff highlighting** (per the strict review's R-044). Hide from the avatar dropdown — only ⌘K → "Show audit log" surfaces it.

**Conversion event.** `audit_export_completed`.

**Reference.** GitHub's audit log. Datadog's audit trail.

---

### 4.17 Admin / SUPER_ADMIN — ⌘K destinations, never in chrome

**Goal.** Operator manages the platform without polluting product nav.

**Locations under ⌘K (`>` mode):**

* `> Admin · users` — today's AdminPage
* `> Admin · feature flags` — today's FeatureFlagsPage
* `> Admin · tier lifecycle` — today's TierLifecyclePage (when it ships)
* `> Admin · payment audit` — today's audit/billing trail
* `> Admin · invoices`
* `> Admin · seller config`
* `> Admin · executions reconcile`
* `> Admin · halt all runs` (with confirm modal)

**Conversion event.** None — operator surface, not user-facing.

**Reference.** The fact you can't see them unless you know to type the verb is the point. Stripe's admin tools live behind `/dashboard` views that aren't in nav.

---

## Section 5 — Onboarding minute-by-minute

### Minute 0 — Landing

User arrives from Google, Twitter, a colleague's link, an HN post.

* Sees the textarea above the fold, with one specific example chip highlighted ("Add OAuth to my Next.js app").
* Submits within 8 seconds. **No signup. No email. No credit card.**
* Backend creates an anonymous "guest project" in a session cookie + a free Anthropic call.

### Minute 1 — First plan

* Plan card appears in 3-7 seconds: *"I'll modify 4 files in your spec…"*
* User clicks **Approve & ship**.
* Per-task progress strip animates: Building · Testing · Reviewing.
* Total ~60-90 seconds for a SINGLE_SHOT spec.

### Minute 2 — First result

* Result page renders. Single status: **✓ Ready**.
* User sees the diff. Reads 2 files.
* Sees: *"Save this run — sign in with GitHub"* — with the run's title pre-quoted in the auth box.

### Minute 3 — Signup

* User signs in with GitHub. Single click.
* Anonymous run binds to the new account; project auto-named after the spec.
* Drops back into `/p/{slug}/r/{runShort}` with a banner *"Saved — you have 4 lifetime runs left. Try another →"*.

### Minute 5 — Second run

* Composer at the bottom of the conversation pre-fills with: "Add tests for the OAuth flow".
* Submit. Build, ship, repeat.

### Minute 30 — Friend invite

* Empty state on the right of the result page: *"Invite a friend — both get +5 runs."*
* User pastes an email. Sent. (Backend: extends `user_activation_event` ledger.)

### Day 1 — Repeat usage

* User submits a third run. Same composer; no friction.
* If they were on a sample, system suggests *"Connect your GitHub repo to keep building."*

### Day 7 — Cap hit

* User on lifetime run #5. Result page banner: *"You've used all 5 free runs. Get unlimited for $20/mo, or invite friends for +5 each."*
* Pro upgrade modal — single Stripe checkout.

### Week 2 — Team

* Pro user clicks "Share" on a run → *"Want a teammate to review? Invite them — both your seats are on Team plan."*
* In-place upgrade to Team + Stripe.

### Month 3 — Enterprise

* Org with 6 seats and growing receives an email: *"Procurement-ready Trust Pack — 4 pages, downloadable now."* (Triggered by seat count + month-over-month growth.)
* Lands on `/trust` → downloads PDF → starts vendor risk assessment.

---

## Section 6 — Mobile

Mobile is **a first-class surface**, not a "best-effort" responsive afterthought. The conversion event for mobile is *"check on a run between meetings."*

### 6.1 Mobile layout primitives

* **Top bar:** logo + project switcher + ⌘K-equivalent button (search icon) + avatar.
* **Body:** the conversation.
* **Bottom bar:** the composer textarea, pinned, expanding to full-screen on tap.
* **No drawer, no sidebar.** Every navigation is a top-bar tap or a `…` menu on a card.

### 6.2 Mobile-specific screens

* **Mobile run-detail (4.5):** scroll vertically; status hero on top, then file tree as a horizontal-swipe carousel, then diff full-bleed. No two-pane on mobile.
* **Mobile project pulse:** swipe through pulse cards; one per swipe.
* **Mobile billing:** single-card, vertical list of plans (no 3-card grid).

### 6.3 PWA + iOS install

Ship as PWA with service-worker + manifest from day 1. iOS users can "Add to Home Screen" and get an app-quality experience. Push notifications for run completion (Pro+).

### 6.4 What we are not building (correctly)

* **No native iOS / Android app.** PWA is enough. Native is a 6-month distraction the product doesn't need.
* **No mobile-only feature.** Every feature is the same on every device. Mobile just renders a different layout.

---

## Section 7 — Pricing & monetization redesign

### 7.1 The ladder (3 rungs visible by default)

| Tier | Price | Quota | Seats | Key features |
|---|---|---|---|---|
| **Free** | $0 | 5 lifetime runs + 5/invite (cap 25) | 1 | All providers · 1 project · 1 repo |
| **Pro** | $20/mo or $200/yr | Unlimited runs | 1 | All providers · BYOK option · Unlimited projects + repos |
| **Team** | $39/seat/mo (min 2 seats) | Unlimited shared pool | 2-20 | All Pro features + SSO + audit + activity feed + shared templates |
| **Enterprise** | "Talk to us" (footer only) | Unlimited | 20+ | All Team features + SAML · SOC 2 · DPA · custom retention · dedicated infra · uptime SLA |

### 7.2 Why this ladder converts better than today's

* **Free is generous-feeling but bounded.** Lifetime not monthly. Sam will use 5 runs in 2 weeks and feel the product worked. Monthly resets feel nickel-and-dimey.
* **Pro is anchored at the Cursor price.** $20/mo is the universal anchor. Don't introduce price psychology by undercutting; match.
* **Team is per-seat, BYOK-disabled by default.** Per-seat is what enterprise procurement understands. Locking BYOK behind Pro keeps Team simple.
* **Enterprise is hidden.** A "Talk to us" link signals legitimacy without cluttering the upsell ladder.
* **No "Max" tier.** The strict review's V141 path consolidates Max → Team. Match.

### 7.3 Default-selected tier on the pricing page

Pro is "Most popular" with a subtle ring. The default tab on the toggle is `Monthly · USD`. India-detected visitors see `Monthly · INR` as default, with a `· USD` toggle right next to it.

### 7.4 Anchor pricing

* Pro at $20 anchors against Cursor.
* Team at $39 anchors against GitHub Copilot Business ($19) and Cody Pro ($19) — premium for the multi-repo + audit + activity story.
* Free at "lifetime" anchors generosity — Cursor gives you ~50 fast requests then nags; ExecuteSpec gives you 5 real PR runs forever.

### 7.5 Free-tier limits — exact values

* **5 lifetime runs.** No monthly reset.
* **+5 runs per friend invited and signed up.** Cap at 25 lifetime runs (5 invites).
* **1 project, 1 repo.** Honest constraint.
* **All providers available.** No provider lock on Free.
* **Anonymous trial allowed (no signup).** First run pre-signup — you only sign in to "save the run".

### 7.6 Upgrade prompts (non-spammy)

* **At lifetime run #4:** banner on the result page — *"1 free run left — upgrade for unlimited or invite a friend for +5."*
* **At lifetime run #5:** modal on result page completion — *"That was your last free run. Get unlimited for $20/mo (cancel anytime) — or invite a friend for +5 each."*
* **At Pro user inviting a teammate:** inline message — *"Add a teammate? Upgrade to Team to share your run pool."* (in-place upgrade)
* **At Team org with 5+ active seats and 30%+ MoM run growth:** monthly email — *"Your team's at scale — here's the Enterprise Trust Pack."*

### 7.7 Seat management UX

In `/team`:

* Empty seats are visible as `── empty seat ──` rows. Visual feedback that you're paying for something unused.
* "Add seats" inline `+ N seats = $X/mo prorated`.
* "Remove seat" → the member drops to "Removed (read-only)". They keep their account; the org just stops paying for them.

### 7.8 Billing transparency

* **No "soft budget" warnings on Pro/Team.** Pro is unlimited; the warning was a Free-tier confusion.
* **Real-time spend on Pro.** "$X.XX this month" in the Account dropdown's avatar tooltip — power users know at a glance.
* **No top-up credits.** Pro is unlimited, full stop. The current top-up flow is removed — it's complexity that confuses Free users and is unnecessary for Pro.

---

## Section 8 — Per-persona conversion funnels

Each persona gets a funnel optimized for their conversion event.

### 8.1 Maya (indie hacker) — funnel = Free → Pro

```
Landing → submit → see result → "save this run" signup
  → 2nd run from same project → "add Stripe to checkout"
  → run #5 → "next run is paid — $20/mo unlimited" → checkout
```

Expected conversion: **Maya hits Pro at lifetime run #5 within 2 weeks of signup.**

### 8.2 Theo (vibe coder) — funnel = Free → Pro after preview-driven WOW

```
Landing → "build me a landing page for a yoga studio" → live preview in 90s
  → "save this site" signup with GitHub → preview URL becomes deploy URL
  → 2nd "build me" → 3rd "build me"
  → run #5 → upgrade or referral
```

Expected conversion: **Theo hits Pro after the second "build me" if the preview impressed.** Otherwise he leaves and we accept that.

### 8.3 Priya (staff eng) — funnel = Pro → Team

```
Landing → multi-repo project setup → 1st cross-repo run → result PR
  → reviewer-merged PR → 2nd cross-repo run with same teammate
  → "share this run" → upgrade to Team to invite → seat added
```

Expected conversion: **Priya skips Free entirely (multi-repo requires Pro), tries Pro for 2 weeks, upgrades to Team when she's ready to bring 1+ teammates.**

### 8.4 Devon (EM) — funnel = Pro/Team → Team expansion

```
Discovers via Priya's invite → joins on Team seat → opens Insights
  → sees "spend by project" + "top wins" → confidence in cost control
  → adds 3 more seats to scale
```

Expected conversion: **Devon adds seats once he sees the Insights screen.** No Insights screen, no expansion.

### 8.5 Amara (compliance) — funnel = Team → Enterprise

```
Devon mentions ExecuteSpec → Amara opens /trust → downloads Trust Pack
  → 5-min vendor review → SSO setup proven via /team → DPA signed
  → 6-figure ARR closed
```

Expected conversion: **Amara converts only with Trust page + SSO config UI in place.** No /trust page = no enterprise sales.

### 8.6 Sam (student) — funnel = Free → invite loop → eventual Pro

```
Class assignment → tries for free → ships PR → asks 4 friends to also try
  → 4 referrals × +5 runs = 20 extra → finishes class
  → 6 months later, internship requires real coding tool → upgrades to Pro
```

Expected conversion: **Sam doesn't convert this quarter. She converts in 6 months.** The free tier's job is to keep her from churning to Cursor; the referral loop is the growth-not-revenue play.

---

## Section 9 — What to throw away (specific, callable list)

The following surfaces are deleted entirely:

| Surface | Replacement |
|---|---|
| `/` (runs list as homepage) | Project home with chat thread |
| Left sidebar | Top bar + ⌘K + Avatar dropdown |
| Breadcrumb | Project switcher in top bar |
| `/runs/new` (redirect-then-modal) | Composer in project home |
| `/dashboard` (gated, broken) | `/insights` |
| `/projects` (silent redirect) | Project switcher dropdown |
| `/admin/tiers` (404'd) | `⌘K > Admin · tier lifecycle` |
| `/admin/feature-flags` in main nav | `⌘K > Admin · feature flags` |
| `/welcome` as separate route | Empty-state of the homepage textarea |
| New-run modal (8 inputs) | Composer (1 textarea + Advanced) |
| Plan-approval modal as blocking gate | Inline plan card in the conversation |
| `Validation report` as separate page | The PR-shaped run detail |
| `Verified N/100` as a single number | `Ready / Needs review / Failed` + 4 evidence chips |
| `Shippable` auto-chip | Deleted entirely |
| `Vibe View` toggle and code path | Deleted |
| `Production` vs `Prototype` toggle | "Plan & review" (default) vs "Quick run" (chip) |
| `/accounting` page (India-tax-forward) | Folded into `/billing` as collapsible "GST (India only)" |
| `/pricing` model-rate matrix as separate page | Folded into a "How is cost calculated?" expander on `/pricing` |
| `Insights` triple split (`/dashboard` + `/exec` + `/admin/analytics`) | One `/insights` page |
| Sticky "+ New mission" CTA | Composer in homepage |
| 11-row plan catalog UI | 3-card pricing grid (DB keeps all 11 rows) |
| "Halt all runs" header button | Confirm-protected ⌘K admin destructive action |
| Project-page Dashboard breadcrumb (broken) | Removed (project home IS the dashboard) |

The following surfaces are kept but renamed:

| Old | New |
|---|---|
| Mission / Launch / Execution | **Run** |
| Validation report | **Run result** (and the PR is the report) |
| Architecture doc | **Code map** |
| AI provider | **Model provider** (in Settings) |
| Spec template | **Saved spec** |
| Feature flags | **Platform flags** (admin-only) |
| Activity feed | **Activity** |
| Audit log | **Audit log** (renamed from "Audit") |

---

## Section 10 — Migration / cutover

The new UX requires a hard cut, not a gradual rollout. Here's why and how.

### 10.1 Why a hard cut

The IA collapse (sidebar → top bar, runs-list → chat) is incompatible with a "two modes" rollout. Half the user base in old IA, half in new IA, fragments support, doc, and team mental models. The product is small enough (V125 tracks ~28 active orgs) that a hard cut is feasible.

### 10.2 The cutover plan

**Week 0 (announce):**

* Email every active user: *"On Friday, ExecuteSpec is moving to a new chat-first UX. Your projects, runs, and PRs are all preserved — only the navigation changes."*
* `/changelog` page lists every IA change with screenshots.
* Old → new URL mapping table on the changelog page.

**Week 1 (deploy with redirects):**

* Old URLs hard-redirect to new ones:
  * `/` → `/p/{most-recent-project}` (or `/p/empty` if no projects)
  * `/runs/{id}` → `/p/{slug}/r/{runShort}`
  * `/dashboard` / `/exec` / `/admin/analytics` → `/insights`
  * `/projects` → opens project switcher dropdown
  * `/runs/new` → composer auto-focused on `/p/{slug}`
  * `/welcome` → empty-state of `/p/empty`
* Banner on first visit: *"New here? Take the 2-min tour."* — links to a short Loom.
* Slack / Twitter / blog post.

**Week 2-4 (support):**

* Pinned in-app `?` help with "What changed" on top.
* Office hours twice a week (Zoom, 30 min, opt-in).

**Week 4+:**

* Old IA components deleted from codebase. Rollback impossible by then.

### 10.3 Deep links preserved

Every shared report link (`/reports/shared/{token}`), every run share, every PR webhook URL stays valid. The new IA reuses the same backend routes; only the frontend shell changes.

### 10.4 What we lose temporarily

* Power-user muscle memory for sidebar nav. They learn ⌘K in 2 days; v0/Linear/Vercel users already have the muscle.
* "Vibe View" if anyone was using it (no evidence anyone is).

### 10.5 What we gain

* The 12 highest-leverage fixes from the strict review's remediation plan all land at once.
* The conversion funnel collapses from 7+ steps to 3.
* Mobile becomes a real surface, not a polite afterthought.

---

## Section 11 — Tracking the conversion lift

Each conversion event from Sections 1, 4, 7, 8 maps to a PostHog `EventName` (V125 already has the ledger):

| Funnel stage | Event | Target |
|---|---|---|
| Marketing → trial | `landing_textarea_submit` | 35% of unique visitors |
| Trial → signup | `signup_completed` (V125) | 60% of submitters |
| Signup → first run | `first_run_started` | 90% of signups |
| First run → first ready | `first_run_ready` | 75% of first runs |
| First ready → second run | `repeat_run_submitted` | 60% in 24h |
| Free → Pro | `tier_upgraded` (Free→Pro) | 25% at lifetime cap |
| Pro → Team | `team_seat_added` | 30% of Pro users in 30d |
| Team → Enterprise | `enterprise_inquiry_submitted` | 5% of Teams in 90d |
| Retention | `repeat_run_submitted` weekly | 50% WAU |
| Trust download | `trust_page_pdf_downloaded` | 90% of enterprise inquiries pre-download |

Dashboard for these metrics lives in `/insights`. Exec/EM personas check it weekly; admin checks daily.

---

## Section 12 — Visual mock summary (per screen, in 1 line)

For the email's exec summary and the design-review readout:

| # | Screen | One-line description |
|---|---|---|
| 4.1 | `/` (logged-out marketing) | Single-textarea homepage with one example chip and ungated trial — like Bolt + v0 + Lovable |
| 4.2 | `/auth` (post-trial signup) | OAuth-first, "save this run" framing |
| 4.3 | `/` (signed-in empty) | Textarea + 3 sample chips — first project auto-creates on submit |
| 4.4 | `/p/{slug}` (project home) | Slack-style chat thread with composer pinned bottom; all runs accumulate inline |
| 4.5 | `/p/{slug}/r/{runShort}` (success) | GitHub-PR-shaped: status hero + file tree + diff + comments |
| 4.6 | `/p/{slug}/r/{runShort}` (failure) | Same shape, "Needs review" headline, suggested fix CTA |
| 4.7 | Project switcher (popover) | Linear-style, search + list + new project |
| 4.8 | `/settings` | Single-page card stack, no sub-routes |
| 4.9 | `/billing` | 3-tier pricing card grid + usage + receipts; "Free is current" badge |
| 4.10 | `/team` | Member list + seat picker + invite-by-email + SSO (Enterprise) |
| 4.11 | `/insights` | Spend by project + top wins + needs attention — collapses 3 today screens into one |
| 4.12 | `/integrations` | Card grid by install count; in-page OAuth |
| 4.13 | `/trust` | Public, footer-linked, 4-page Trust Pack downloadable |
| 4.14 | `/pricing` (public) | Same 3-card grid as billing + FAQ + cost-calculator drawer |
| 4.15 | Help (⌘K → ?) | Glossary + shortcuts + docs in one modal |
| 4.16 | Audit log (⌘K) | Today's audit page + field-level diffs; admin-only |
| 4.17 | Admin tools (⌘K > admin) | Tier lifecycle, feature flags, payment audit, halt-all-runs (with confirm) |

---

## Section 13 — What's NOT in this redesign

For honesty and scope clarity:

* **No iOS / Android native apps.** PWA only.
* **No CLI redesign.** The CLI lives separately (per `docs/plans/cli-plan.md` from CLAUDE.md).
* **No VS Code extension redesign.** Same — separate roadmap.
* **No marketing-site copy beyond the landing/pricing/trust pages I covered.**
* **No new product surfaces.** Every feature called out in the strict review or the codebase grep is reachable in the new IA. Nothing new is invented.
* **No engineering refactor proposals.** That's what the (now-dropped) remediation plan was for. This is a UX brief, not an engineering plan.

---

## Section 14 — Methodology

Sources for this redesign:

* `/tmp/executespec-strict-competitive-ux-review.md` — re-read in full
* `frontend/CLAUDE.md` and the root `CLAUDE.md` for the feature inventory
* Live `@*Mapping` grep over `src/main/java/com/navneet/executespec/` to confirm every backend feature has a UX home in the new IA
* `frontend/src/pages/` directory listing to confirm every existing page has a redesign or a "deleted" decision
* The strict review's persona archetypes (Maya, Theo, Priya, Devon, Amara, Sam) used unchanged

References used per section are in-line with each design.

---

*End of greenfield UX redesign. Built from the strict review's evidence, the codebase's feature inventory, and a single opinion: **the textarea is the homepage, the PR is the result, and the chat is the navigation.***
