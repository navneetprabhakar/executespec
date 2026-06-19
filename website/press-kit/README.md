# ExecuteSpec Press Kit

_For Product Hunt launch, advertising, and press contacts. Last refreshed 2026-04-28 (Wave 5)._

## Contact

- **Founder**: Navneet Prabhakar
- **Email (press)**: `admin@executespec.ai`
- **Email (sales / enterprise)**: `admin@executespec.ai`
- **Email (support)**: `admin@executespec.ai`
- **Website**: `https://executespec.ai`
- **App**: `https://dev.executespec.ai`
- **Public 30-day demo (no signup)**: `https://dev.executespec.ai/v3/exec-demo`
- **Trust posture**: `https://dev.executespec.ai/v3/trust`
- **Product Hunt**: (link after launch)

## One-liner

> ExecuteSpec ships AI-written pull requests you can actually merge — for one repo or for twenty.

## Two-liner

> ExecuteSpec ships AI-written pull requests you can actually merge — for one repo or for twenty.
> Plan, build, test, review, open a PR. ~80% of real runs land Ready, and you see the verdict (Ready / Needs review / Failed) on every one of yours.

## Longer description (suitable for press)

> ExecuteSpec is an AI coding platform that ships pull requests your team will actually merge. Every run flows through a single pipeline — **planner → plan-approval gate → executor → reviewer → final build + test gate** — and ends with a deterministic verdict (Ready / Needs review / Failed) attached to the PR. The verdict is derived from real signals (build status, test pass rate, reviewer outcome, spec adherence), not LLM self-rating. ~80% of real runs land Ready against real codebases.
>
> Built for the merge step rather than the typing step: where Cursor, GitHub Copilot, and Continue suggest code at the cursor and leave validation to you, ExecuteSpec runs the validation itself and only opens a PR when it's ready (or labels it Needs review / Failed when it isn't).
>
> Multi-repo project workspaces with architecture docs, tech-debt panels, project memory, and RAG-powered project chat. Four tiers: **Free** (5 lifetime runs + 5 per friend you invite, cap 25), **Pro** ($20/mo, unlimited managed runs solo), **Team** ($39/seat/month, minimum 2 seats, BYOK + per-seat collab + audit log + SSO), **Enterprise** (SOC 2 + signed DPA + dedicated infra + custom MSA). Anonymous trial — no signup for the first preview. Built for US + global engineering teams.

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
│   ├── og-hero.png                         (1200×630 — re-rendered 2026-04-28 with v3 selling-point)
│   └── README.md
├── ss-01-dashboard.png                      (NB: pre-v3 capture; re-shoot scheduled)
├── ss-02-run-detail.png                     (NB: pre-v3 capture; re-shoot scheduled)
├── ss-03-project-overview.png               (NB: pre-v3 capture; re-shoot scheduled)
├── ss-04-architecture-viewer.png            (NB: pre-v3 capture; re-shoot scheduled)
├── ss-05-validation-report.png              (NB: pre-v3 capture; re-shoot scheduled)
├── ss-05b-validation-polished.png           (NB: pre-v3 capture; re-shoot scheduled)
├── ss-extra-score-trend.png                 (NB: pre-v3 capture; re-shoot scheduled)
└── CAPTURE_GUIDE.md                         (SOP for shooting the next round in v3)
```

The 7 screenshots are from the pre-v3 visual identity. They show v2 dashboard, v2 run-detail, v2 architecture viewer, and v2 validation report — useful for context but **not** what the v3 product looks like today. A re-shoot session is queued under a separate work item; in the meantime use the live product at `https://dev.executespec.ai/v3/landing` for any Product Hunt / blog hero imagery.

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

> ExecuteSpec is a spec-driven AI coding platform that turns a plain-English requirement into a reviewable pull request. The workflow is plan, approve cost, build, test, review, and open a PR with a deterministic verified score and evidence ledger. Multi-repo project workspaces, plan approval, project memory, PR/code review, and BYOK are built in. Pricing: Free (15 runs/month), Pro ($29/month for 30 managed runs), Team ($39/seat/month from 1 seat, BYOK), Enterprise (custom contract + DPA path). Try free at https://executespec.ai.

## Press availability

- Written quotes — 24h turnaround
- Async video quotes — 48h turnaround
- Live demo calls (30 min) — book at `admin@executespec.ai`
- Product walkthroughs (60 min, for editorial) — book at `admin@executespec.ai`
- Custom benchmark runs (e.g. "ship a feature on a real codebase, verdict + diff captured for the article") — book at `admin@executespec.ai`
