# Product Hunt Launch Kit — ExecuteSpec

> **Refreshed 2026-06-10** (RL-2026-06-019 truth pass). All pricing/claims in this kit now match the
> billing DB (Free / Pro $29 / Team $39/seat / Enterprise) and the honest-claims policy
> (`docs/enterprise-one-pager.md`: no SOC 2, no SAML-end-to-end, no benchmark numbers until the
> rebuilt eval lands). Source of truth for live copy: `website/index.html` + the app's
> `PublicPricingPage`.

_Prepared: 2026-04-21 · refreshed 2026-06-10_

Everything we need to hit "ship it" on Product Hunt. US-first framing since that's where our traffic is coming from.

## 1 · Listing fields

| Field | Limit | Value |
|---|---|---|
| **Name** | 40 | `ExecuteSpec` |
| **Tagline** | 60 | `AI that ships PRs your team actually merges` (44) |
| **Alt tagline** | 60 | `Spec in. Verified, test-backed pull request out.` (49) |
| **Description** | 260 | See §2 |
| **Topics** | 3 | `Developer Tools` · `Artificial Intelligence` · `Productivity` |
| **Pricing tag** | — | `Freemium` (Free → $29 Pro → $39/seat Team BYOK → Enterprise) |
| **Platforms** | — | `Web` |
| **Website** | — | `https://executespec.ai` |
| **Demo video** | — | TODO — 60-90s: spec → plan gate (with price) → live execution → verified score → real PR |

## 2 · 260-char description (paste-ready)

> ExecuteSpec turns a plain-English spec into a verified pull request. You approve the plan — and the price — up front; a planner→worker→reviewer pipeline codes it, runs your tests, and ships a PR with a deterministic 0–100 verified score. Not LLM self-rating.

(254 chars — fits.)

## 3 · Maker's first comment (post at t+00:00)

> Hey Product Hunt ✋
>
> I built ExecuteSpec because "AI that writes code" is everywhere but "AI you can *delegate* to" still isn't. Every other tool hands you a diff and shrugs. We don't.
>
> **What's different:**
> - **You approve the plan — and the price — first.** The planner decomposes your spec into tasks with a cost estimate. Credits are charged only when you approve, and refunded if the run fails. No surprise PRs, no surprise bills.
> - **Verified score, not vibes.** Every run ends with a 0–100 score computed from the actual build passing, your actual test suite running in the workspace, and the reviewer agent finding (or not finding) wiring issues. Not the LLM grading its own homework.
> - **Real PRs on your repos.** One repo or twenty — multi-repo projects with architecture docs, per-repo codemaps, a tech-debt panel, and project chat that knows your codebase.
> - **Autofix on-ramps.** One click turns PR review comments, tech-debt categories, or code-scan findings into their own patch runs, each with its own PR.
>
> **How pricing works (because this is what trips everyone up):**
> - **1 credit ≈ $5 of model spend.** A run costs 1–N credits based on its estimate, shown before you approve.
> - **Free**: 15 runs/month, no card. Small specs, $3/run cap.
> - **Pro ($29/mo or $289/yr)**: 30 runs/month on managed keys — we pay the LLM. $5/run cap; bring your own key to lift it.
> - **Team ($39/seat/mo, from 1 seat)**: BYOK — unlimited runs on your own Anthropic/OpenAI/Gemini keys, zero per-run markup. Org spend caps + approval thresholds.
> - **Enterprise**: custom contract + signed DPA, per-org model matrix control, per-run model pins.
>
> **The hard truths:**
> - We're early. No SOC 2 yet (it's on the roadmap — happy to fill in your security questionnaire), and we publish only what we can measure.
> - Runs take 5–20 minutes. This is delegation, not autocomplete — start one, go do something else, come back to a scored PR.
>
> Would love your feedback — what would it take for you to *trust* an AI-opened PR enough to review it like a teammate's?

## 4 · FAQ (for the thread)

**Q: How is this different from Cursor / Windsurf / Claude Code?**
A: Those are copilots — they help you write code inline, and you validate the result. ExecuteSpec is a delivery pipeline: spec goes in, a verified PR comes out. If you live in your IDE, Cursor is great. If you want to hand off a spec and come back to a PR with a deterministic verified score, that's us. Many devs use both.

**Q: Can I use my own API keys?**
A: Yes. Team ($39/seat/mo) is BYOK — you pay Anthropic/OpenAI/Google directly, zero per-run markup; the seat fee covers the platform. Pro can optionally add a key to lift its $5/run cap. Free + Pro otherwise run on our managed keys.

**Q: Why should I trust the verified score?**
A: Because it's deterministic and additive over real signals: code delivered (30) + build passing (25) + your own test suite's pass rate (20) + reviewer verdict (20) + unfixed criticals (5), averaged over the slots that actually fired. No LLM self-rating. The formula is public.

**Q: What gets generated — full apps or just snippets?**
A: Full working changes: Spring Boot backends, React + Vite frontends, Python CLIs, multi-repo features across existing codebases. There's also a library of readymade solutions (Stripe checkout, OAuth login, RAG with pgvector, …) and modernization recipes (JS→TS, Express→Fastify, …).

**Q: GDPR / data handling?**
A: Specs go only to the LLM provider you pick; nobody trains on your code. Workspaces are wiped at run end. GDPR export + erasure are built into the product; signed DPA available. We're an early-stage company — we publish what's true today, and SOC 2 Type II is on the roadmap, not claimed.

**Q: What's the catch?**
A: Two: (1) HIGH-complexity runs are genuinely expensive — that's exactly why the plan gate shows you the estimate and charges only on approval. (2) The verified score tells you where to *start* reviewing, not to skip reviewing — "Needs review" means exactly that.

## 5 · Launch checklist

- [ ] **Day -14**: Assets ready — tagline, 260-char, 6 screenshots (press-kit has them: dashboard, run detail, project overview, architecture viewer, validation report, score trend), 60-90s demo video, GIF hero
- [ ] **Day -14**: Truth-pass ops checklist DONE (`docs/launch-readiness-2026-06-10.md`) — prod tier ladder verified, website deployed with `app.executespec.ai` CTAs, OG unfurl tested
- [ ] **Day -7**: Hunter identified (warm DM, not cold). Back-up hunter confirmed
- [ ] **Day -7**: Community warm-up: 3-5 IndieHackers / Hacker News "Show HN" posts about the problem (not the product)
- [ ] **Day -7**: Founder pricing live (50% off lifetime, first 100 Pro) — Stripe coupon + Razorpay offer created and test-purchased
- [ ] **Day -3**: Final screenshots + video locked. Upload to PH as draft
- [ ] **Day -1**: Email list warm-up (current Free users) — "we're launching tomorrow, founder pricing for the first 100"
- [ ] **Day 0, 00:01 PT (12:31 IST)**: Launch. Post first maker comment immediately
- [ ] **Day 0, +30 min**: Slack + Discord + LinkedIn + Twitter push
- [ ] **Day 0, every 2h**: Reply to every comment. Don't upvote your own.
- [ ] **Day 0, +12h**: Live demo in Product Hunt comments (async video response)
- [ ] **Day +1**: Follow-up post — "yesterday's findings" with the top 3 user questions
- [ ] **Day +7**: Email all sign-ups from PH with onboarding tips + "what's next"

## 6 · Assets status

1. ✅ **`website/images/og-hero.png`** — exists (1200×630), referenced by both the marketing site and the app's OG tags. Verify it resolves on prod before launch.
2. ✅ **Screenshots** — `website/press-kit/` has ss-01…ss-05b + score-trend. Re-capture any that show old pricing/chrome.
3. ⬜ **Demo video** — 60-90s. Script: (a) paste spec → plan gate shows tasks + price (10s), (b) approve → watch tasks execute via live log (20s), (c) reviewer passes (10s), (d) verified score + real PR on GitHub (15s), (e) share the verified report link (5s).
4. ⬜ **Verified-report gallery** — run 5–10 real specs against known OSS repos; publish the public share links as the proof section (replaces benchmark numbers until the rebuilt eval lands).
5. ⬜ **Hunter** — someone with >500 followers who has hunted ≥3 dev-tools in the last 90 days.

## 7 · Copy variants

**Hero (current landing):**
`AI that ships pull requests your team actually merges.`

**Variant B (delegation frame):**
`Delegate the spec. Approve the plan and the price. Merge the PR.`

**Variant C (technical):**
`Spec in. Verified 0–100-scored PR out. Plan gate. Multi-repo. BYOK.`

**Twitter blurb (280 chars):**
`Most AI coding tools hand you a diff and shrug.
ExecuteSpec runs planner → worker → reviewer on your spec, runs YOUR tests, and opens a PR with a deterministic 0–100 verified score. You approve the plan — and the price — first. Live on @ProductHunt today.
https://executespec.ai`

**LinkedIn post (engineering-leader audience):**
> If your team ships 50 PRs a week via an "AI coding" tool, how many of those PRs do your seniors actually trust without a full read?
>
> ExecuteSpec is built on the premise that trust is a measurable output, not a vibe. Every run ends with a 0–100 verified score derived from: did the build pass, what fraction of YOUR test suite passed in the workspace, and did the reviewer agent find unresolved wiring issues. No LLM self-rating.
>
> And before any of that runs, you approve the plan — with a cost estimate — at a gate. Credits charge on approval, refund on failure.
>
> Free: 15 runs/mo. Pro $29/mo. Team $39/seat BYOK (your keys, zero markup). Live on Product Hunt today: [link]

## 8 · Advertising kickoff (US-targeted)

**Google Ads — search campaign (start at $50/day):**

Keywords: `ai code review tool` · `spec driven development tool` · `cursor alternative` · `claude code alternative` · `ai coding agent for teams`

Ad group 1 — alternatives steal:
> **H1:** ExecuteSpec — Ship, Don't Just Write
> **H2:** 0-100 Verified Score on Every Run
> **H3:** Delegate Specs, Merge Verified PRs
> **Description:** Plan gate with price up front. Deterministic verified score. Multi-repo, BYOK. Free 15 runs/mo; Pro $29/mo; Team $39/seat.
> **CTA:** Start free → no card

Ad group 2 — problem-aware:
> **H1:** Stop Babysitting AI Code
> **H2:** Your Tests Decide the Score
> **H3:** Verified Code, Not Just Code
> **Description:** Spec → plan you approve → worker → reviewer → PR with a 0-100 verified score from build + tests + review. Free tier available.
> **CTA:** See how it works →

**Meta Ads — retargeting + lookalike (start at $30/day):**
Creative: 15s screen-capture — plan gate with price → live log → verified score banner → real GitHub PR.
Copy: `AI tools write code. ExecuteSpec ships it — with a verified score your team can trust.`

**LinkedIn Ads — engineering leader targeting ($75/day):**
Targeting: Senior SWE / EM / VP Eng at 50–500-person companies, US + Canada + UK.
Carousel: 1 "The problem" (AI PRs nobody trusts) · 2 "Plan gate with price" · 3 "Verified score from YOUR tests" · 4 "Pricing" · 5 "Start free".

## 9 · Success metrics

| Metric | Day 0 target | Day 7 target | Day 30 target |
|---|---|---|---|
| PH upvotes | 500 | — | — |
| PH comments | 50 | — | — |
| Landing page uniques | 5k | 15k | 50k |
| Free sign-ups | 200 | 600 | 2,000 |
| Pro conversions | 10 (5%) | 40 | 150 |
| Team seats | 2 | 8 | 30 |
| $ MRR added | $368 | $1,472 | $5,520 |

_MRR model: Pro = $29 × count (founder-50 halves the first 100), Team = $39 × seats. Track via the in-app 6-stage activation funnel (SIGNUP → PROJECT_CREATED → SPEC_SUBMITTED → PLAN_APPROVED → RUN_COMPLETE → PR_MERGED)._

## 10 · Post-launch follow-ups (P1)

1. **Stripe top-up flow** — `BillingController.purchaseTopup` routes only to Razorpay today; Stripe path needs the checkout-URL variant + webhook handler + `TopupPackage.stripePriceId`.
2. **Country-aware gateway routing** — route `country == "IN"` → Razorpay, else Stripe (today it's the `defaultGateway` string).
3. **Stripe refund path** — `BillingService.issueProratedRefund` TODO; upgrades on Stripe currently skip the prorated refund silently.
4. **TEAM yearly plans** — V165 deactivated the $49-era yearly rows and no $39-based replacement landed; decide + seed if annual Team demand shows up.
5. **Benchmark re-run** — publish the rebuilt-harness SWE-bench numbers (with the honest small-N framing) once they land; until then the proof asset is the verified-report gallery.
