# Product Hunt Launch Kit — ExecuteSpec

> **⚠ NEEDS REFRESH (flagged 2026-04-28).** Hero/tagline copy + tier names in
> this kit are pre-tier-coherence-fix. The new selling-point is
> *"AI that ships pull requests your team will actually merge"* with
> ~80% verified score as the proof. Tier names: FREE / PRO $20/mo /
> TEAM $39/seat (was MAX $29) / ENTERPRISE. FAQ + ad copy below should be
> rewritten before launch — the current v3 landing is the source of truth at
> `frontend/src/v3/pages/V3Landing.tsx` and `website/index.html`.
>
> Launch checklist + Day -1/0/+1 cadence below still valid.

_Branch: `feature/ux-overhaul-v1` · Prepared: 2026-04-21_

Everything we need to hit "ship it" on Product Hunt. US-first framing since that's where our traffic is coming from.

## 1 · Listing fields

| Field | Limit | Value |
|---|---|---|
| **Name** | 40 | `ExecuteSpec` |
| **Tagline** | 60 | `Specify once. Ship verified code.` (36) |
| **Alt tagline** | 60 | `The spec-first AI coding platform that actually reviews itself.` (59) |
| **Description** | 260 | See §2 |
| **Topics** | 3 | `Developer Tools` · `Artificial Intelligence` · `Productivity` |
| **Pricing tag** | — | `Freemium` (Free → $20 Pro → $29 Max + BYOK → Enterprise) |
| **Platforms** | — | `Web` |
| **Website** | — | `https://executespec.ai` |
| **Demo video** | — | TODO — 60-90s Loom: plan-approval gate → sequential worker → reviewer → verified 92 score |

## 2 · 260-char description (paste-ready)

> ExecuteSpec turns structured specs into verified, tested code. Multi-agent pipeline (planner → sequential worker → reviewer) with a 0-100 verified score derived from build + test + reviewer signals — not LLM self-rating. Multi-repo projects, BYOK, $20/mo.

(248 chars — fits.)

## 3 · Maker's first comment (post at t+00:00)

> Hey Product Hunt ✋
>
> I built ExecuteSpec because "AI that writes code" is everywhere but "AI that actually ships code you can trust" still isn't. Every other tool hands you a diff and shrugs. We don't.
>
> **What's different:**
> - **Verified score, not vibes.** Every run ends with a 0-100 score derived from the actual build passing, the actual tests running, and the reviewer agent finding (or not finding) wiring issues. Not the LLM grading its own homework.
> - **Plan gate before execution.** You see the decomposed plan + cost estimate and approve before a single credit burns. No surprise bills.
> - **Multi-repo projects.** Architecture doc, tech-debt panel, per-repo codemaps, project chat (RAG). The workspace understands your codebase, not just the file you pasted in.
> - **Figma URL → design-compliance score.** Paste a Figma link, get back a 0-100 design-fidelity rating alongside the code.
> - **React Native + Expo first-class.** Generate a full mobile app + scan QR code preview.
>
> **How pricing works (because this is what trips everyone up):**
> - **$1 = 1 credit.** No token translation. No dark-pattern credit math. Same rate for subscriptions and top-ups.
> - **Free**: 5 runs, see the whole platform including a read-only demo project.
> - **Pro ($20/mo)**: 20 credits = ~20 LOW runs, 5 HIGH runs, or 10 Mobile runs (mix as you like). All 3 providers, managed keys.
> - **Max ($29/mo + BYOK)**: Unlimited runs on your own Anthropic/OpenAI/Gemini keys. Zero markup.
> - **Enterprise**: Execution View (multi-repo launches), JIRA/Confluence inbound, SSO/SAML, RBAC.
>
> **The hard truths:**
> - Gemini HIGH-complexity runs currently use 10× more tokens than April baseline — we're working on it. Anthropic is cheapest on LOW/MEDIUM right now.
> - Mobile accuracy caps at 65-78 across all 3 providers — planner tuning for RN+Expo is top of backlog.
> - We publish real cost data, not aspirational marketing numbers. See `website/pricing-analysis.md` in the repo.
>
> Would love your feedback — what trips you up on other spec/AI coding tools? What would make you switch?

## 4 · FAQ (for the thread)

**Q: How is this different from Cursor / Windsurf / Claude Code?**
A: Those are IDE copilots — they help you write code inline. ExecuteSpec is a delivery pipeline: spec goes in, verified PR comes out. Different problem. If you live in your IDE, Cursor is great. If you want to hand off a spec and come back to a merged PR with a 92/100 verified score, we're built for that.

**Q: Can I use my own API keys?**
A: Yes. Max tier ($29/mo) is BYOK with zero markup — you pay Anthropic/OpenAI/Gemini directly, we charge flat for the platform. Free + Pro use our managed keys (we pay the provider).

**Q: What gets generated — full apps or just snippets?**
A: Full working projects. Latest runs have shipped: Spring Boot backends, React + Vite frontends, Python CLIs, Go microservices, React Native + Expo Android/iOS apps (with Hermes bundle), Rust libraries. Worker iteration caps are 30/50/75 by complexity.

**Q: Why should I trust the verified score?**
A: Because it's deterministic. Final build passed (20 points) + test pass rate (20 points scaled) + reviewer verdict (CLEAN=20 / FIXED=15 / UNRESOLVED=5 / FAILED=0) + baseline 40 − 5 per unfixed critical. No LLM self-rating. Formula is public in the codebase.

**Q: GDPR / data handling?**
A: Specs go only to the LLM provider you pick. All three contract to not train on customer data. Workspace data is GCP us-east4, encrypted at rest, TLS in transit. 30-day retention Free, 90-day Pro/Max, unlimited Enterprise. Full DPA on request.

**Q: What's the catch?**
A: Two:
1. **HIGH-complexity runs are expensive.** Real cost: $1.28-$2.65 depending on provider. We transparently charge 4 credits ($4) for these and tell you the count before you approve. No stealth billing.
2. **Mobile is still early.** RN+Expo runs ship but accuracy sits at 65-78. Planner tuning in progress.

**Q: Roadmap?**
A: Short-term: Gemini token bloat fix, mobile accuracy pass, Execution View GA. Medium: Anthropic prompt caching (big cost win), provider-aware credit pricing (you pay less for Gemini HIGH vs Anthropic HIGH). Long: a marketplace of validated spec templates.

## 5 · Launch checklist

- [ ] **Day -14**: Assets ready — tagline, 260-char, 6 screenshots (dashboard, run detail, project view, architecture doc, validation report, mobile preview), 60-90s demo video, GIF hero
- [ ] **Day -14**: `og:image` fixed — see §6
- [ ] **Day -7**: Hunter identified (warm DM, not cold). Back-up hunter confirmed
- [ ] **Day -7**: Community warm-up: 3-5 IndieHackers / Hacker News "Show HN" posts about the problem (not the product); build upvote affinity
- [ ] **Day -3**: Final screenshots + video locked. Upload to PH as draft
- [ ] **Day -1**: Email list warm-up (current Free users) — "we're launching tomorrow, here's your Max tier discount"
- [ ] **Day 0, 00:01 PT (12:31 IST)**: Launch. Post first maker comment immediately
- [ ] **Day 0, +30 min**: Slack + Discord + LinkedIn + Twitter push
- [ ] **Day 0, every 2h**: Reply to every comment. Don't upvote your own.
- [ ] **Day 0, +12h**: Live demo in Product Hunt comments (async video response)
- [ ] **Day +1**: Follow-up post — "yesterday's findings" with the top 3 user questions
- [ ] **Day +7**: Email all sign-ups from PH with onboarding tips + "what's next"

## 6 · Missing assets (blocking)

1. **`website/images/og-hero.png`** — 1200×630 OG image. Currently the `<meta property="og:image">` tag at `website/index.html:30` points to a file that doesn't exist. Social shares have no preview card.
2. **Logo variants** — we have `website/images/logo.png` referenced but no `website/images/` directory yet. Need: logo light, logo dark, logo mark-only (square), favicon .ico.
3. **Screenshots (6 needed)** — dashboard, run detail (showing verified score + signal breakdown chip), project overview tab (showing tech debt panel + generated docs), architecture viewer, validation report, mobile preview with QR code.
4. **Demo video** — 60-90s Loom. Script: (a) paste spec → plan gate (5s), (b) approve + watch tasks execute via SSE (20s), (c) reviewer passes (10s), (d) verified 92 score + open PR (15s), (e) mobile app QR preview (10s).
5. **Hunter** — someone with >500 followers who has hunted at least 3 products in the last 90 days. Ideally a dev-tools enthusiast.

## 7 · Copy variants

**Hero variant A (current landing):**
`Specify once. Ship verified code.`

**Hero variant B (more aggressive):**
`AI that ships merged PRs. Not AI that writes code for you to clean up.`

**Hero variant C (technical):**
`Specs in. Verified 0-100 scored code out. Multi-agent. Multi-repo. BYOK.`

**Twitter blurb (280 chars):**
`Most AI coding tools hand you a diff and shrug.
ExecuteSpec runs a planner → worker → reviewer pipeline with a deterministic 0-100 verified score from build + tests + reviewer signals. $20/mo, 20 credits, no token math. Live on @ProductHunt today.
https://executespec.ai`

**LinkedIn post (for engineering-leader audience):**
> If your team ships 50 PRs a week via an "AI coding" tool, how many of those PRs does your SEM actually trust without a full read?
>
> ExecuteSpec is built on the premise that "trust" is a measurable output, not a vibe. Every run ends with a 0-100 verified score derived from:
> - Final build status (binary: did `mvn package` / `npm run build` pass?)
> - Test pass rate (fraction, scaled)
> - Reviewer agent verdict (did the wiring pass find unresolved issues?)
>
> No LLM self-rating. No "looks good to me" hallucinations. Just signals that map to real CI/CD gates.
>
> $20/mo for 20 credits (20 runs). $29/mo + BYOK for unlimited. Enterprise for multi-repo + SSO + RBAC.
>
> Live on Product Hunt today: [link]

## 8 · Advertising kickoff (US-targeted)

**Google Ads — search campaign (start at $50/day):**

Keywords:
- `ai code review tool` (high intent)
- `spec driven coding` (category creation)
- `cursor alternative` (competitor steal)
- `claude code alternative`
- `ai pair programmer enterprise`

Ad group 1 — alternatives steal:
> **Headline 1:** ExecuteSpec — Ship, Don't Just Write
> **Headline 2:** 0-100 Verified Score on Every Run
> **Headline 3:** The Cursor Alternative for Teams
> **Description:** Spec-driven AI coding with deterministic scoring, multi-repo projects, BYOK. $20/mo Pro, $29/mo Max unlimited.
> **CTA:** Start free → 5 runs, no card

Ad group 2 — problem-aware:
> **Headline 1:** Stop Babysitting AI Code
> **Headline 2:** Multi-Agent Pipeline With Verification
> **Headline 3:** Verified Code, Not Just Code
> **Description:** Structured spec → planner → worker → reviewer → 0-100 score. Multi-repo, Figma→code, RN+Expo. Free tier available.
> **CTA:** See how it works →

**Meta Ads — retargeting + lookalike (start at $30/day):**

Creative: 15s screen-capture video — plan gate → live SSE log → verified 92 score banner → "Ship this" button flashing.

Audience: lookalike of current Free sign-ups; exclude existing Pro/Max.

Copy: `AI tools write code. ExecuteSpec ships it. 0-100 verified score on every run.`

**LinkedIn Ads — engineering leader targeting ($75/day):**

Targeting: Senior Software Engineer / Engineering Manager / VP Eng at 50-500 person companies in the US + Canada + UK.

Creative: carousel — slide 1 "The problem" (50% of AI-generated PRs need rework), slide 2 "Verified score", slide 3 "Multi-repo projects", slide 4 "Pricing", slide 5 "Start free".

## 9 · Success metrics

| Metric | Day 0 target | Day 7 target | Day 30 target |
|---|---|---|---|
| PH upvotes | 500 | — | — |
| PH comments | 50 | — | — |
| Landing page uniques | 5k | 15k | 50k |
| Free sign-ups | 200 | 600 | 2,000 |
| Pro conversions | 10 (5%) | 40 | 150 |
| Max conversions | 2 | 8 | 30 |
| $ MRR added | $220 | $880 | $3,270 |

_MRR model: Pro = $20 × count, Max = $29 × count. Does not include top-ups._

## 10 · Post-launch follow-ups (P1)

1. **Wire Stripe top-up flow** — `BillingController.purchaseTopup` currently only routes to Razorpay. Need: polymorphic `TopupOrderInitResult` (Razorpay order fields OR Stripe checkout URL), `RunTopupService.initiatePurchase(gateway, successUrl, cancelUrl)` branch, `BillingService.processStripeWebhook` `type=topup` handler, `TopupPackage.stripePriceId` column. ~4-6 files, 1 migration, billing-critical (test against Stripe test mode).
2. **Country-aware gateway routing** — `BillingEntity.getCountry()` currently doesn't exist. Add to interface + `AppUser`/`Organization`. `PaymentGatewayRouter.resolveDefault` branch: `country == "IN"` → Razorpay, else → Stripe. Today the fallback is `defaultGateway` string.
3. **Currency-gateway-map actually wired** — `application.yml:587-591` declared but never read. Trivial: inject `@Value("#{${billing.currency-gateway-map}}")` into the router.
4. **Stripe refund path** — `BillingService.issueProratedRefund` has a TODO comment at line 961. Upgrades on Stripe currently skip the prorated refund silently.
5. **`og:hero.png` + `website/images/` directory** — blocking for social shares.
6. **Demo Loom + 6 screenshots** — blocking for Product Hunt gallery.
