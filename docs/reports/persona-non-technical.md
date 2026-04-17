# Non-technical user persona — assessment

> Persona: **Maya**, a product manager at a 30-person SaaS company. She has
> run code in the browser before but cannot read a stack trace. She uses
> ExecuteSpec because her engineers are bottlenecked and she wants to ship a
> small internal tool herself. Her mental model is "I describe what I want, I
> get a thing I can show in a meeting."
>
> Method: walked the live UI as the admin user, then re-read every screen
> through Maya's eyes. Identified each moment where the product expects
> developer-level prior knowledge.

## Two flows tested

1. **Normal Run** — `New Run` from the side panel, type a spec, watch it
   build, get a preview link.
2. **Project execution** — `Open` a project, `Launch`, pick repos, wait for
   PRs to land.

## Journey 1 — normal Run

### What Maya sees on `New Run`
- A modal asking for a *"User request"* in a text area.
- A `Source` selector (`MANUAL | GITHUB_NEW | GITHUB_EXISTING | REST`).
- An `Execution mode` (`PROTOTYPE | PRODUCTION`).
- An `AI provider` dropdown (`Anthropic | Google | OpenAI`).
- An `Auto-merge` checkbox; a `Skip approval` checkbox.

### What stops Maya
- **"User request" is the only label.** No example, no template, no "what makes
  a good request" hint. Maya has nothing to model her input on.
- **`Source = REST`** is one of four values — it's an API-internal label that
  leaked into the UI. Maya has no idea what to pick.
- **`PROTOTYPE` vs `PRODUCTION`** has no human-language explanation. The
  cost difference (PROTOTYPE skips production gates) and quality difference
  are real but invisible.
- **`AI provider` exposes brand names** but the cost / speed / quality
  tradeoff is encoded in the small grey tags `Premium / Cheapest / Fastest`.
  Maya doesn't know what "Premium" means in this context — premium quality?
  premium price? both?
- **`Auto-merge`** uses the engineering term "merge". Maya knows the word but
  not its meaning in this product (merge to where? what gets reviewed?).
- **No estimate visible at submit.** She doesn't know what this will cost
  her *until* she's already submitted and the plan-approval gate fires.
  CLAUDE.md says credits are charged at plan approval — but Maya doesn't know
  that, and the modal doesn't say so.

### What Maya sees while it runs
- Status pills like `PLANNING / WORKING / AWAITING APPROVAL / REVIEWING / COMPLETE`.
  Two of these (`WORKING`, `REVIEWING`) make sense; the rest don't tell her
  what to do.
- A stepper across the top: *Planning · Approval · Tasks · Compilation ·
  Review · Merge · Preview · Testing · Report*. Nine steps; most have no
  user action. Some are checked, some are pending. Maya doesn't know which
  ones she's responsible for.
- **`AWAITING APPROVAL`** is the worst case. It looks scary because it's
  amber — but it just means "we made a plan, we want you to OK it before we
  spend your credits". The UI does not say that in those words.

### What Maya sees when it finishes
- Success badge `Code generated · 4 tasks complete · 88/100 accuracy · B`.
- Buttons: `Re-run`, `GitHub`, `Download`, `Archive`.
- A `Validation Report` row with `Passed`, an `A 88/100`, file count, and
  `Clean run, all checks passed`.
- A tab strip `OVERVIEW / PLAN / TASKS / GRAPH / LIVE LOG / CODE / PREVIEW / CHAT`.

### Maya's questions the UI doesn't answer
- *"Where is the actual thing? I can see PREVIEW — is that it?"* (Yes — but
  the tab is the 7th of 8 in a strip rendered in monospace caps that all
  look like devtools panels.)
- *"Is the build broken? It says PARTIAL · 97/100. Which is true?"* The new
  v2.5.10 statusReason tooltip helps here, but only if Maya hovers — she
  won't.
- *"What does GRAPH mean?"* (It's the task DAG visualisation; for Maya it's
  noise.)
- *"Why does it say 'Code generated' but the badge is grey-blue?"* (Because
  COMPLETE maps to `accent-emerald`/`accent-blue` depending on tier — the
  legend isn't surfaced.)

### Verdict on Journey 1
Maya can probably get to a working preview if she submits an unambiguous
request (e.g. *"Build me a single-page calculator in HTML/JS with buttons 0–9
and + - × ÷"*) and OK's whatever gates pop. She will not understand:
- why she had to approve a plan
- which provider she should pick
- what most of the tabs do
- why the run cost what it cost

Her satisfaction depends entirely on whether the artefact works — which the
eval results suggest it does for tiny specs (S1 100% pass) and partially for
small specs (S2 5/12 cases on OpenAI).

## Journey 2 — project execution

### What Maya sees on `Projects`
- Page title "Projects" + subtitle *"Group runs into projects and control
  team access."* Then a list of cards: name, ACTIVE, provider tag, brief
  description, `Open` and `View Runs` buttons.
- `+ New Project` top-right.

### What stops Maya
- *"What is a project for?"* The subtitle says "group runs and control team
  access" — both of those are admin concepts. Maya wants to know *"is this
  the right thing for me?"*. The answer ("a project links your repos,
  remembers your past work, and lets multiple runs share context") is
  nowhere on this page.
- **`ACTIVE` next to `Anthropic (Claude)`** look like the same kind of pill.
  One is a state, one is a configuration. Maya can't tell.
- **`+ New Project`** opens a form asking for name, description, AI provider.
  No template, no "start with a sample project" path.

### Inside an empty project
- Six panels: KPIs (all 0 / —), tab strip, Project settings, Project
  summary, Workspace health, Recent submissions, Recent pull requests, Score
  trend. **All empty.**
- The `Launch` button is bright blue, top-right, **always clickable** even
  with zero repos linked.
- The hint *"Click 'Launch' to kick off a cross-repo execution"* is a single
  greyed-out line in the Submissions panel.
- The `Repos` tab has *"No repositories linked yet. Click 'Add repository'
  to get started."* — the only honest signal in the UI that the next step
  is **link a repo**.

Maya, looking at this empty dashboard, will most likely click `Launch`
because it's the brightest thing on screen. That will fail because she has
no repos. She will not know why.

### Inside a linked-repo project
- The Workspace health panel comes alive: *"navneetprabhakar/hostel-be-v3
  main up to date - f1c7642 + f5210d2 set up webhook 1 open PR"*.
- The dense mono SHA pair is unreadable to Maya.
- The "set up webhook" link is a configuration action that takes her to
  GitHub admin (PAT scope `admin:repo_hook` required) — a developer-only
  setting.
- The architecture summary panel renders a paragraph correctly:
  *"This hostel management system comprises a frontend repository …"* — this
  IS Maya-readable and useful.

### Launching a project execution
- Maya clicks `Launch`, picks the repos, types her requirement.
- The UI takes her through `SETUP → ANALYSING → ARCH_REVIEW → PLANNING →
  AWAITING_APPROVAL → EXECUTING`.
- **`ARCH_REVIEW`** is a moment where the user is asked to OK a "unified
  architecture document" before the planner runs. Maya has zero context for
  what to look at — the doc is markdown describing inter-repo relationships
  she doesn't understand. She'll click Approve.
- **`AWAITING_APPROVAL`** is the second gate. Now she's looking at a list of
  per-repo solutions with task counts. She'll click Approve again.
- **`EXECUTING`** runs the per-repo workers in parallel; PRs land at the end.

### Maya's questions the project flow doesn't answer
- *"What's the difference between an execution and a run?"* The product
  uses both terms; "executions" live under projects, "runs" are the
  per-solution unit; the UI uses both terms inconsistently.
- *"Why two approval gates?"* She'd accept one, two feels like nagging.
- *"What is happening in ANALYSING?"* (Building codemaps. The frontend
  doesn't even have a colour for this status — it falls through to grey,
  per the UX review.)
- *"Where will my PRs go?"* The default repo + branch are configurable in
  Project settings, but the launch flow doesn't surface "you are about to
  open PRs against `navneetprabhakar/hostel-be-v3#main`" before she clicks.

### Verdict on Journey 2
Project execution is built for an engineer who already understands
multi-repo PR workflows. Maya can complete it under guidance from her dev
team but will not adopt it independently. The chat tab (when it works — it
doesn't on Anthropic-default projects right now per Bug B in the eval
report) is the closest thing to her mental model: ask a question, get an
answer with file citations.

## Specific UI strings to rewrite for Maya

| Current copy | What Maya hears | Better |
|---|---|---|
| `User request` | "type something" | `What do you want me to build?` |
| `Source: MANUAL / REST / …` | "?" | hide entirely from the UI |
| `Execution mode: PROTOTYPE / PRODUCTION` | "?" | `Goal: explore an idea (cheaper) / ship to users (slower, with reviews)` |
| `Skip approval` | "skip what?" | `Auto-approve the plan (you'll be charged immediately)` |
| `AWAITING APPROVAL` | "Did I do something wrong?" | `Plan ready — review and start` |
| `ANALYSING` | (no colour, no meaning) | `Reading your code` |
| `ARCH_REVIEW` | (no colour) | `Checking how repos fit together` |
| `Workspace health · up to date - f1c7642 + f5210d2 · 1 open PR` | wall of mono | `Last synced just now · 1 pending pull request` |
| `Verified 90 · Est. 88` | "two scores?" | `Build passed · 88 % quality estimate` |
| Stepper: `Planning · Approval · Tasks · Compilation · Review · Merge · Preview · Testing · Report` | engineer's mental model | collapse to 4: `Plan → Build → Review → Done`; expand on hover |

## Three top recommendations from Maya's POV

1. **A first-run guide.** The first time a user creates an account, walk them
   through a 60-second sample run with pre-filled spec and pre-explained
   gates. Today they're dropped onto a list of historical runs that
   reference projects they don't have.

2. **Plain-language status copy.** Two changes earn the most: rename
   `AWAITING APPROVAL` to `Plan ready — start?` and rename `ANALYSING / ARCH_REVIEW`
   to `Reading code / Checking architecture`. Same backend, different label.

3. **Estimate before submit, not after plan.** A spec entered into the
   `New Run` modal should show "estimated cost: 1 credit" *before* the user
   submits. The estimation service exists (`/api/projects/{id}/estimate`)
   and is used on Project launches; bring it to the global New Run modal.

## What works well for Maya already

- The **preview iframe**. The PREVIEW tab on a completed run renders the
  generated app in-product. That is the closest thing to "I can see what
  was built" — high success.
- The **archive / re-run / GitHub** action row. Three buttons, named in
  English, do what they say. No ambiguity.
- The **chat-when-it-works** experience. The OpenAI-defaulted project chat
  (the one whose temperature parameter still works) returns *"This project
  appears to be a hostel-related application split into two repos: …"* —
  exactly the level of detail Maya wants.
- The **score badge** as a single number. Maya doesn't read the dimensions
  but sees "88 — that's a B, that's good".
