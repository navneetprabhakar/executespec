# UX live walkthrough — raw notes (2026-06-10)

Method: Vite dev server :5173 against local backend :8080 (old code, pre-Fireworks). ENTERPRISE test
user (revalidate-ent@test.local). Captures: ~800px native (renders the app's mobile/tablet chrome),
1440px emulated (desktop layout, low-res), 375×812 mobile preset, dark + light themes, IDE + Console
chromes. Admin-only surfaces (/benchmark, /admin) NOT walked live (admin creds file out of session
scope) — assess from code.

## Defects found live (verified)
1. **/api-keys deep-load = permanently blank screen.** root has 0 children, no error, no redirect, no
   404 fallback, console clean. Either the route is missing from the route table with no catch-all,
   or a guard renders null. Direct-URL load reproduces 100% (2 loads). BUG-grade.
2. **Run-detail Code tab on mobile width has NO file selector.** a11y tree confirms: single file view
   (.dockerignore) + Content/Diff/Wrap/Copy only — the other 6 changed files are unreachable on
   mobile/tablet chrome. Violates the mobile-first standing rule.
3. **Status-bar tier badge contrast failure (both themes).** ENTERPRISE badge = olive/green text on
   the blue status bar — borderline unreadable in dark, worse in light. Token not adapted for
   on-blue surface.
4. **Status bar horizontal overflow at 375px** — right-side items ("light"/theme button) clipped.
5. **Billing page contradicts run history.** Billing: "Runs 0/∞ · Spent $0.00 · Successful runs (30d) —"
   while All-runs shows 2 runs, $0.06 + $0.02, 3d/7d ago, same account. Whatever the backend cause,
   two surfaces disagree on money — trust-eroding.
6. **Notification bell: "9+" badge / 29 unread on a fresh test account** — notification spam; no
   auto-read or digesting. (Also: badge says 9+ while a11y label says 29 — cap is fine, volume isn't.)
7. **Login form silently ignores programmatic fill + click** (React controlled inputs) — not a user
   bug per se, but there was NO visible error/spinner feedback path either when submit is a no-op;
   couldn't verify wrong-password UX (untested).

## Provider/model leakage into customer UI (feeds docs 09/10)
- Compose: PROVIDER select (Anthropic) is a top-level field, peer with MODE + SPENDING CAP.
  MODEL · BETA select shows "Default (org / system matrix)" — internal jargon ("system matrix") in
  customer-facing copy.
- Run result: RUN card shows "Provider openai / Worker gpt-5.4"; action row has "Try anthropic" /
  "Try gemini" buttons (lowercase enum-style provider names as button labels).
- Runs list rows show provider name ("openai") but NOT the verified score — the product's headline
  metric is absent from the list view while a vendor name is present.
- Org settings F3 grid: rows are Anthropic/Openai/Gemini/Openrouter/Deepseek ("Openai"/"Openrouter"
  mis-capitalized); OpenRouter row defaults to **claude-sonnet-4-6** (YAML tier-map oddity leaking
  to customers — an aggregator presented as a provider with another vendor's model as its default);
  raw model ids (deepseek-v4-flash-native) instead of display-labels; tier names Planning/Workhorse/
  Lightweight are internal pipeline vocabulary.

## Run result (flagship surface) — assessment
- GOOD: verdict-first (Shippable pill + 94/100 hero), SIGNALS grid honest about unmeasured (Tests "—"),
  submitted prompt block, cost + credits visible.
- BAD: raw enum `CLEAN_COMPLETION` displayed top-right; 10-action flat "WHAT NOW" row mixes Push to
  GitHub (primary) with Delete/Archive/Fork/Try-provider (no hierarchy/overflow menu); tab strip has
  10 tabs (Overview/Code/Tasks/Timeline/Logs/Validation/Cost/PR/Tests/Chat) — several near-empty for
  a 1-task sandbox run; "Code 7" tab count vs SIGNALS "Files 3" disagree.
- Diff view: added lines = full-bleed saturated green rows (entire viewport green for a new file);
  harsh vs conventional subtle tinting. No per-file add/del counts visible.
- PRODUCT smell: a 3-line Python spec produced .dockerignore/Dockerfile/.env templates/secrets.yml —
  worker over-scaffolding visible to the customer as noise files in "their" change.

## Home (IdeWelcome)
- ~65% of canvas empty for a logged-in user with history; content = 2 small run cards + empty
  projects. No score trend, no spend summary, no "what needs attention".
- Run cards: status + provider + absolute timestamp "07/06/2026, 13:37:49" (locale-ambiguous,
  seconds noise). Console chrome uses "3d ago" — inconsistent date language between chromes.
- "Press ⌘K to jump anywhere" copy shown on touch/mobile too.

## Compose
- Clean structure (textarea-first, samples, sandbox default). Good: spending cap + mode visible.
- "Build my spec" (SpecChat) trigger is understated relative to its value.
- PROVIDER/MODEL/MODE/SPENDING CAP exposed as equal-weight columns — decision overload for the
  core funnel; defaults buried in jargon.

## Chromes & theme
- IDE chrome (default): VS Code furniture (activity bar, EXPLORER, status bar, editor tabs) wraps
  surfaces that are mostly monitoring/reading. The metaphor costs jargon (Workspace/Explorer) and
  pixels, and the flagship flows (approve plan, read verdict) don't benefit from it. Desktop IDE
  at 1440 looks competent; at 800-1024 it switches to the mobile chrome.
- Console chrome (es:console=1): better-focused home (hero CTA, Needs-attention groupings, relative
  times, inline create-one empty state). Direction looks right for a customer control plane.
- Light theme: works, tokens mostly adapt; status-bar badge + blue bar issues above.
- Typography: JetBrains Mono for EVERYTHING (headings, body, buttons). Distinctive, on-brand for
  devs; readability cost on paragraph copy + number-dense tables; tracking tight at small sizes.
  Consider mono-for-code/data + a humanist sans for UI copy.

## Misc
- Two brand marks on login (white E left, amber E right panel) — inconsistent.
- Login right-panel bullets include "BYOK bypasses the cost cap" — insider language pre-signup.
- Coral/red links ("Forgot password?", "Create an account") read as error/danger affordances.
- Status bar carries "Try Console ⤴ / IDE · Beta" — an A/B-ish escape hatch in prod chrome.

## Not assessed live (code-only via agents)
- /benchmark (SUPER_ADMIN), /admin, project workbench tabs (no project on test account — creating
  one requires a linked repo/PAT), plan-gate + live-run surfaces (needs a live run; consider a
  sandbox run later if budget allows).
