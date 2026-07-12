# ExecuteSpec Press Kit

_For Product Hunt launch, advertising, and press contacts._
_Last refreshed 2026-07-13 (ExecuteSpec 2.0 / CLI 1.0 release track)._ 

## Contact

- **Founder**: Navneet Prabhakar
- **Email (press)**: `admin@executespec.ai`
- **Email (sales / enterprise)**: `admin@executespec.ai`
- **Email (support)**: `admin@executespec.ai`
- **Website**: `https://executespec.ai`
- **App**: `https://dev.executespec.ai`
- **Trust posture**: `https://dev.executespec.ai/trust`
- **Product Hunt**: (link after launch)

## One-liner

> ExecuteSpec turns a plain-English spec into a reviewable pull request across one or more linked repos.

## Two-liner

> ExecuteSpec turns a plain-English spec into a reviewable pull request across one or more linked repos.
> Plan, approve, build, test, review, and open a PR with a deterministic Ready / Needs review / Failed verdict and reloadable evidence. We do not publish a universal success rate; outcomes depend on the repository, spec, provider, and validation gates.

## Longer description (suitable for press)

> ExecuteSpec is an AI coding platform for structured, reviewable delivery. Every run flows through a single pipeline — **planner → plan-approval gate → executor → reviewer → final build + test gate** — and ends with a deterministic verdict (Ready / Needs review / Failed) attached to the PR. The run report separates deterministic signals (build and test results) from reviewer assessments and the internal verified-score summary; it is not an independent benchmark or merge guarantee.
>
> Built for the merge step rather than the typing step: where Cursor, GitHub Copilot, and Continue suggest code at the cursor and leave validation to you, ExecuteSpec runs the validation itself and only opens a PR when it's ready (or labels it Needs review / Failed when it isn't).
>
> Multi-repo project workspaces with architecture docs, tech-debt panels, project memory, RAG-powered project chat, and a web agent control plane with durable turns and local-hands handoff to CLI/VS Code. Current self-serve tiers: **Free** (200 AI credits/month), **Pro** ($20/mo, 2,000 AI credits), **BYOK** ($50/mo single-person org, own provider keys), **Team** ($50/seat/month, minimum 2 seats, BYOK + collaboration + audit log), **Enterprise** (custom contract + DPA path). Built for US + global engineering teams.

## Founding story (1 paragraph)

> I spent 18 months using every "AI coding" tool I could get my hands on — Cursor, Windsurf, Claude Code, Bolt.new, Lovable, v0, Replit Agent. They all have the same shape: generate a diff, hand it to me, shrug. My team ended up doing the same review work we'd always done, just on slightly weirder code. ExecuteSpec started as a bet that the **merge step** — plan → execute → validate → label — is where the real value lives, not the code-writing step. The Ready / Needs review / Failed verdict on every PR is what makes this concrete: a label that maps to real CI signals, not a vibe-check from the same model that wrote the code. Everyone else fights over typing speed; we fight over what your reviewer will approve.

## Positioning vs each major competitor

> | vs | We add what they don't |
> |---|---|
> | Cursor | Multi-file dispatch, multi-repo, validated PR with verdict (Cursor wins on completion latency) |
> | Devin | Plan-approval gate (you see + approve before any code is written), pricing transparency |
> | v0 / Bolt / Lovable | Real PR to your existing repo, backend + multi-file (they ship into their sandbox) |
> | GitHub Copilot Workspaces | Multi-repo, multi-provider, explicit plan-approval gate |
> | Sourcegraph Cody | Agent autonomy (Cody is mostly Q&A + complete) |
> | Claude Code (CLI) | Web-shareable runs, audit log export, team admin |

## Assets

```
website/press-kit/
├── README.md                                ← you are here
├── og-hero-template/                        ← source for the 1200×630 OG card
│   ├── template.html
│   ├── render.js
│   ├── og-hero.png                         (1200×630 — current social preview)
│   └── README.md
├── current/                                 (canonical current Console-era captures)
│   ├── 01-compose-start.png                 (Start work / spec composer)
│   ├── 02-console-home.png                  (Console home / decisions)
│   ├── 03-work-runs.png                     (Work list)
│   ├── 04-projects.png                      (Projects index)
│   ├── 05-run-result.png                    (Run result / evidence shell)
│   ├── 06-billing.png                       (Billing dashboard)
│   ├── 07-plans.png                         (Plans)
│   ├── 08-project-detail.png                (Project overview)
│   ├── 09-project-architecture.png          (Project system map / architecture)
│   └── contact-sheet-current.png            (QA contact sheet)
├── ss-01-dashboard.png                      (historical pre-v3 capture; do not use)
├── ss-02-run-detail.png                     (historical pre-v3 capture; do not use)
├── ss-03-project-overview.png               (historical pre-v3 capture; do not use)
├── ss-04-architecture-viewer.png            (historical pre-v3 capture; do not use)
├── ss-05-validation-report.png              (historical pre-v3 capture; do not use)
├── ss-05b-validation-polished.png           (historical pre-v3 capture; do not use)
├── ss-extra-score-trend.png                 (historical pre-v3 capture; do not use)
├── ../media/hero.webm                       (current illustrative walkthrough; synthetic demo data)
├── ../media/hero.vtt                        (caption track for the walkthrough)
└── CAPTURE_GUIDE.md                         (SOP for shooting the next round)
```

The old `ss-*` screenshots are from the pre-v3 visual identity. They show v2 dashboard, v2 run-detail, v2 architecture viewer, and v2 validation report — useful for historical context but **not** what the current product looks like. The landing-page walkthrough includes a caption track and an expandable transcript so the synthetic sequence is accessible without sound.

Use `current/` for investor, website, Product Hunt, and deck work. These captures were re-shot on 2026-06-19 from the current Console shell at a 1440×900 viewport, using deterministic demo data through a local screenshot API shim. The website walkthrough was regenerated on 2026-07-12 from the current agent-control-plane story. Treat all screenshots and video as product-demo assets, not real customer data, production metrics, or benchmark evidence.

## Brand guidelines (current — to be unified in the color-codes pass)

- **Primary accent**: brand blue `#5B8DEF` (v3) / `#0066cc` (legacy website — to converge)
- **Brand gradient**: orange→amber `linear-gradient(to right, #F97316, #FBBF24)` for the italic headline accent (matches `<span class="it">`)
- **Success accent**: teal-green `#7AD1C8` (v3) / `#17B169` (website)
- **Warn accent**: amber `#FBBF24`
- **Error accent**: red `#EF4444`
- **Background (dark, default)**: `#0B0D10` (v3) / `#000000`–`#0a0a0a` (website)
- **Surface**: `#14171C` / `#1B1F26` / `#242A33` (v3 surface ladder)
- **Text**: primary `#ECEEF2`, secondary `#C9D1DB`, muted `#8A94A6`
- **Typography**: `Geist` (body + UI), `Geist Mono` / `JetBrains Mono` (code), `Instrument Serif` italic for brand-accent words
- **Tone**: direct, technical, honest about tradeoffs. No hype, no rocket emojis. We cite real cost data, including when it's unflattering to us (see `website/pricing-analysis.md`).

> Note: the website orange/amber palette and the v3-app blue/teal palette currently live as two distinct visual systems. A unified color-codes spec is being prepared — once locked, all six "Primary / Brand gradient / Success / Warn / Error / Background" tokens above converge to one source-of-truth.

## Boilerplate (for use in articles, partner listings, etc.)

> ExecuteSpec is a spec-driven AI coding platform that turns a plain-English requirement into a reviewable pull request. The workflow is plan, approve cost, build, test, review, and open a PR with a deterministic verified score and evidence ledger. Multi-repo project workspaces, plan approval, project memory, PR/code review, and BYOK are built in. Pricing: Free (200 AI credits/month), Pro ($20/month for 2,000 AI credits), BYOK ($50/month single-person org), Team ($50/seat/month from 2 seats), Enterprise (custom contract + DPA path). Try free at https://executespec.ai.

## Press availability

- Written quotes — 24h turnaround
- Async video quotes — 48h turnaround
- Live demo calls (30 min) — book at `admin@executespec.ai`
- Product walkthroughs (60 min, for editorial) — book at `admin@executespec.ai`
- Custom benchmark runs (e.g. "ship a feature on a real codebase, verdict + diff captured for the article") — book at `admin@executespec.ai`
