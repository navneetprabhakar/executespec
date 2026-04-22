# ExecuteSpec Launch Checklist

_Compiled 2026-04-21 · regenerated with explicit numbering 2026-04-22_
_Live site: executespec.ai · Cloudflare Worker `square-bar-e1dc`_

Every action item grouped by urgency and numbered for unambiguous reference. Quote any item as `P0-14`, `P1-3`, etc.

## Numbering contract (never changes)

- **IDs `P0-1` through `D-8` are permanent.** Once assigned, an item keeps its ID forever.
- An item's status only flips between `[ ]` and `[x]`. Never renumbered, never re-ordered, never deleted.
- If a genuinely new item appears later, it gets the next unused number in its section (e.g., `P0-29`). Existing IDs stay fixed.
- The only thing that re-ranks is the "Top 3 highest-leverage actions" block near the bottom — that's a **view**, not a stable-ID list. It's labeled as such so it's not confused with the P0-N scheme.

---

## Status summary (at a glance)

| Section | Done | Pending | Total |
|---|---|---|---|
| **P0** Blocking | 24 | 4 | 28 |
| **P1** Day 0 launch | 0 | 14 | 14 |
| **P2** Week 1 | 2 | 13 | 15 |
| **P3** Weeks 2-4 | 0 | 12 | 12 |
| **P4** Month 1+ | 0 | 8 | 8 |
| **D** Deferred | 0 | 8 | 8 |
| **Overall** | 26 | 59 | 85 |

**🎉 Search-engine discovery section (P0-1 → P0-14) is 100% complete.** Bing + Google both verified, sitemaps submitted, indexing requested, Cloudflare Bot Fight confirmed off. The only remaining P0 items are assets (screenshots, OG image, demo video) and ops (Stripe env vars).

---

## P0 · Blocking (must clear before driving traffic)

### Search-engine discovery (P0-1 → P0-14)

- [x] **P0-1** · `robots.txt` deployed with LLM-crawler allow rules + sitemap pointer
- [x] **P0-2** · `sitemap.xml` deployed with 16 URLs (incl. `#tour` + `#compare` anchors)
- [x] **P0-3** · `llms.txt` deployed (LLM answer-engine summary)
- [x] **P0-4** · `site.webmanifest` deployed + linked from `<head>`
- [x] **P0-5** · `BingSiteAuth.xml` deployed + **Bing ownership verified**
- [x] **P0-6** · Meta description tightened to 144 chars (was 338, over Bing's 160 limit)
- [x] **P0-7** · `<title>` tightened to 59 chars (was 76, over Bing's 70 limit)
- [x] **P0-8** · Keywords list expanded to 41 entries (20 competitor alternatives + 21 category)
- [x] **P0-9** · Submit sitemap in Bing Webmaster Tools → `https://executespec.ai/sitemap.xml` submitted
- [x] **P0-10** · Request indexing for `/` in Bing → URL inspection submitted
- [x] **P0-11** · Verify Google Search Console (property verified; prerequisite for P0-12 / P0-13)
- [x] **P0-12** · Submit sitemap in Google Search Console
- [x] **P0-13** · Request indexing for `/` in Google Search Console
- [x] **P0-14** · Cloudflare Bot Fight Mode / Super Bot Fight Mode confirmed OFF for executespec.ai

### Site content + assets (P0-15 → P0-24)

- [x] **P0-15** · Landing page pricing locked ($20 = 20 credits; $1 = 1 credit)
- [x] **P0-16** · Project Workspace dedicated section (key offering positioning)
- [x] **P0-17** · LinkedIn company page wired (`linkedin.com/company/executespec`) — schema, contact card, footer
- [x] **P0-18** · Product Tour section deployed (5 tabbed mock views)
- [x] **P0-19** · Comparison table deployed (ExecuteSpec vs 5 competitors)
- [x] **P0-20** · 6 new per-tool FAQ entries deployed + reflected in JSON-LD `FAQPage` schema
- [ ] **P0-21** · Replace placeholder `og-hero.png` (68KB Pillow-generated) with a real designed 1200×630 social card — blocks high-quality social shares
- [x] **P0-22** · Shoot 6 product screenshots — **5 of 6 captured** as 1440×900 HiDPI PNGs to `website/press-kit/` (ss-01…ss-05 + bonus score-trend). Shot 6 (mobile QR) deferred — needs live Expo preview session.
- [ ] **P0-23** · Record 60-90s Loom demo video — script in `website/product-hunt-launch.md §6.4`
- [ ] **P0-24** · Swap mock HTML tour panels for real screenshots — _attempted then reverted 2026-04-22_. Real screenshots from dev data (`ss-05-validation-report.png` + `ss-03-project-overview.png`) showed too much red/orange (legitimate failures + tech-debt bars) for marketing landing. Reverting to green-state styled HTML mocks until we can seed a project with clean/high-score runs for cleaner captures. Real PNGs still live in `website/press-kit/` for the PH gallery — just not embedded in the tour.

### Billing (launch-safe but pre-first-transaction) (P0-25 → P0-28)

- [x] **P0-25** · Stripe wired as prod default gateway in `application-prod.yml` (env-gated)
- [x] **P0-26** · Landing page USD-first with India auto-detect
- [x] **P0-27** · `PlansPage.tsx` USD default with `navigator.language` + timezone geo-detect
- [ ] **P0-28** · Ops: set 5 Stripe env vars on prod GCE (`STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRO_PRICE_ID`, `STRIPE_ENTERPRISE_PRICE_ID`). Fallback: `BILLING_STRIPE_ENABLED=false` + `BILLING_DEFAULT_GATEWAY=razorpay`

---

## P1 · Day 0 (launch-day sequence)

### T-24h prep (P1-1 → P1-4)

- [ ] **P1-1** · Reserve Product Hunt launch slot (PH allows 24h-advance scheduling)
- [ ] **P1-2** · Confirm hunter (>500 followers, 3+ hunts in last 90 days) briefed + has assets
- [ ] **P1-3** · Dry-run: load `https://executespec.ai/` in incognito on desktop + mobile, click every tab and link, confirm no broken paths
- [ ] **P1-4** · Email warm list: existing Free-tier users get "launching tomorrow" with early-bird Max discount code (create the code first)

### T-0 launch (00:01 PT / 12:31 IST) (P1-5 → P1-10)

- [ ] **P1-5** · Product Hunt post goes live
- [ ] **P1-6** · Maker's first comment posted immediately (script in `website/product-hunt-launch.md §3`)
- [ ] **P1-7** · Tweet announcement thread from `@executespec` (copy in `product-hunt-launch.md §7`)
- [ ] **P1-8** · LinkedIn post from `linkedin.com/company/executespec` (copy in §7)
- [ ] **P1-9** · Hacker News post: `Show HN: ExecuteSpec — spec-driven AI coding with verified 0-100 scores` — highest single-source backlink + referral
- [ ] **P1-10** · Indie Hackers product-launch post

### Response cadence (P1-11 → P1-14)

- [ ] **P1-11** · Reply to every PH comment within 2h of posting
- [ ] **P1-12** · Respond to every HN + LinkedIn + Twitter comment (rolling)
- [ ] **P1-13** · Post async video response in PH thread at ~T+12h
- [ ] **P1-14** · Publish "launch day behind-the-scenes" from Navneet on dev.to + Medium at ~T+24h

---

## P2 · Week 1 (authority while search engines crawl)

### Handle claiming (5 min each) (P2-1 → P2-6)

- [x] **P2-1** · `github.com/executespec` organization claimed — verified live (HTTP 200, title "executespec · GitHub")
- [ ] **P2-2** · `@executespec` on Twitter/X
- [ ] **P2-3** · `@executespec` on YouTube (channel for demo videos)
- [x] **P2-4** · `dev.to/executespec` claimed — verified live (HTTP 200, title "Executespec - DEV Community"), profile filled with Our story + Our stack
- [ ] **P2-5** · `indiehackers.com/product/executespec` _— deferred, lower priority per user 2026-04-22 · spot-check: `/executespec` returns 200, confirm ownership if/when claimed_
- [ ] **P2-6** · Crunchbase profile (free — shows up in Google Knowledge Panel)

### Directory listings (P2-7 → P2-11)

- [ ] **P2-7** · **alternativeto.net** — submit as alternative to Cursor, Windsurf, Claude Code, GitHub Copilot, Lovable, Bolt.new, v0, Devin, Replit Agent. Biggest single lever for "X alternative" search intent. ~1 week approval.
- [ ] **P2-8** · G2 listing (free, developer tools category) — collect 3-5 honest reviews from early users
- [ ] **P2-9** · Capterra listing (free)
- [ ] **P2-10** · TrustRadius listing (free)
- [ ] **P2-11** · StackShare tool listing

### Founder byline campaign (P2-12 → P2-14)

- [ ] **P2-12** · Post 1: "Why your AI-written code doesn't compile — the spec-driven fix" → dev.to + LinkedIn article
- [ ] **P2-13** · Post 2: "What a verified 0-100 score actually means" → Medium + dev.to
- [ ] **P2-14** · Post 3: "Building multi-repo AI pipelines: what we learned" → personal blog + LinkedIn

Each post: reference ExecuteSpec as the brand, link to `executespec.ai`, 1,500–2,500 words, include code samples.

### Cloudflare purge check (P2-15)

- [ ] **P2-15** · Add the cache-purge OAuth scope to Wrangler login so post-deploy cache invalidation works programmatically (currently purge API returns 401)

---

## P3 · Weeks 2-4 (SEO compounding)

### Content expansion — single-page sites rank poorly (P3-1 → P3-3)

- [ ] **P3-1** · Add `/blog/` with 6-10 initial posts (byline campaign + 3-7 more)
- [ ] **P3-2** · Add `/docs/` with 5-8 concept pages: "What is spec-driven coding", "How credits work", "Verified score explained", "Project workspace architecture", "Dual-Mode execution", "BYOK setup guide"
- [ ] **P3-3** · Each new page gets unique `<title>`, `<meta description>`, schema.org `BlogPosting` / `TechArticle`; cross-link internally to homepage

### Long-tail SEO targets (P3-4 → P3-9)

Easier to rank on than `executespec` alone:

- [ ] **P3-4** · `spec-driven coding platform`
- [ ] **P3-5** · `AI code review with verified score`
- [ ] **P3-6** · `multi-repo AI coding`
- [ ] **P3-7** · `Cursor alternative enterprise`
- [ ] **P3-8** · `BYOK AI coding platform`
- [ ] **P3-9** · `how to migrate Spring Boot 2 to 3 with AI` (use-case SEO)

### Visibility / PR (P3-10 → P3-12)

- [ ] **P3-10** · Create Wikidata entity (`wd:Q` for ExecuteSpec) — feeds Google Knowledge Graph
- [ ] **P3-11** · Pitch to 3 dev-focused newsletters: Changelog, TLDR Tech, Console
- [ ] **P3-12** · Submit to 2-3 DevTools / DevX podcasts for interview

---

## P4 · Month 1+ (paid + partnerships)

### Paid acquisition (P4-1 → P4-4)

- [ ] **P4-1** · Google Ads: $1/day on exact `executespec.ai` + `executespec` — brand protection, cheap
- [ ] **P4-2** · Google Ads: $50/day on intent keywords (`spec driven coding`, `cursor alternative`, `ai code review tool`)
- [ ] **P4-3** · LinkedIn Ads: $75/day targeting Senior SWE / EM / VP Eng at 50-500p companies in US/CA/UK (carousel creative)
- [ ] **P4-4** · Meta Ads: $30/day retargeting + lookalike (15s screen-capture creative)

### Partnerships + marketplaces (P4-5 → P4-8)

- [ ] **P4-5** · Cloudflare Apps marketplace listing (if eligible)
- [ ] **P4-6** · JetBrains Plugin listing (if a plugin exists)
- [ ] **P4-7** · Slack App Directory listing
- [ ] **P4-8** · GitHub Marketplace listing (for the PR integration)

---

## D · Deferred (not launch-blocking — ship when bandwidth allows)

Ranked by business impact. Full detail in `docs/reports/session-handoff-2026-04-21.md §5`.

- [ ] **D-1** · **Stripe top-up wiring** (P1 priority post-launch; `BillingController.purchaseTopup` currently Razorpay-only — 4-6 files, billing-critical, test in Stripe test mode first)
- [ ] **D-2** · Country-aware gateway routing — `BillingEntity.getCountry()` + `PaymentGatewayRouter.resolveDefault` branch
- [ ] **D-3** · Wire `currency-gateway-map` in PaymentGatewayRouter (trivial — `@Value` injection, today declared but unused)
- [ ] **D-4** · Stripe refund path — `BillingService.issueProratedRefund` line 961 TODO
- [ ] **D-5** · Gemini 10× MEDIUM token bloat fix — planner prompt tuning specific to Gemini (cost lever, see `website/pricing-analysis.md §7`)
- [ ] **D-6** · Mobile accuracy floor — RN+Expo planner prompt tuning (quality lever, currently 65-78)
- [ ] **D-7** · `ModelResolver` strict-provider bypass (Bug #1c from v2.5.9 eval)
- [ ] **D-8** · Real Product Tour screenshots — swap mock HTML panels for `<img>` tags once P0-22 is done

---

## Success metrics (Day 0 → Day 30)

From `website/product-hunt-launch.md §9`:

| Metric | Day 0 | Day 7 | Day 30 |
|---|---|---|---|
| PH upvotes | 500 | — | — |
| PH comments | 50 | — | — |
| Landing page uniques | 5k | 15k | 50k |
| Free sign-ups | 200 | 600 | 2,000 |
| Pro conversions (5%) | 10 | 40 | 150 |
| Max conversions | 2 | 8 | 30 |
| $ MRR added | $220 | $880 | $3,270 |
| GSC impressions | 0 | ~500 | ~5,000 |
| `site:executespec.ai` indexed pages | 0 | 5+ | 15+ |

---

## Artefact locations

| Artefact | Path |
|---|---|
| Deployable site files | `website/` (also `~/Desktop/site/` mirror) |
| Site deploy command | `cd website && npx wrangler deploy` |
| Worker name | `square-bar-e1dc` (account `97c95815ad8f3dd328d78de56091993a`) |
| Custom domains | `executespec.ai`, `www.executespec.ai` (dashboard-managed, deploy-safe) |
| Pricing source of truth | `website/tier-decision.md` |
| Real cost data | `website/pricing-analysis.md` |
| PH launch kit | `website/product-hunt-launch.md` |
| Press kit | `website/press-kit/README.md` |
| Session handoff (context) | `docs/reports/session-handoff-2026-04-21.md` |
| This checklist | `docs/reports/launch-checklist-2026-04-21.md` |
| Press + sales contact | `admin@executespec.ai` (consolidated — single inbox until team grows) |

---

## Top 3 highest-leverage actions right now

> **This is a view, not a stable-ID list.** The `1. / 2. / 3.` ranking below re-orders as items get checked off. Per the numbering contract at the top of this doc, the `P0-N` IDs referenced inside each bullet never change.

(Updated after Bing + Google indexing all went live.)

1. **P0-22** (shoot 6 real product screenshots) — 60-90 min. Biggest remaining unlock: fuels both the PH gallery AND the tour-panel asset swap (P0-24). Nothing else on the list touches two downstream items at once.
2. **P2-7** (submit to alternativeto.net) — 20 min. Single-highest-authority backlink for every "X alternative" search intent. Matches 20 of our 41 keywords.
3. **P0-28** (ops: wire 5 Stripe env vars on prod GCE) — ~15 min of ops work. Until this lands, US paid conversions fall back to Razorpay, which doesn't ideally serve US cards.

Everything else compounds off these three.

---

## How to re-verify a done item

To confirm a checked-off item is still true:

| Type | How to re-verify |
|---|---|
| **Deployed file (P0-1..5, P0-18..20)** | `curl -sI https://executespec.ai/<path>` → expect 200 + correct content-type |
| **Meta tag fix (P0-6..8)** | `curl -s "https://executespec.ai/?cb=$(date +%s)" \| grep -m1 '<pattern>'` |
| **Bing verification (P0-5)** | Bing Webmaster Tools → Home → property status = "Verified" |
| **Pricing lock (P0-15)** | View `/#pricing` section — should show $20/20 credits on PRO card |
| **Tour section (P0-18)** | `/#tour` loads with 5 tabbed panels |
| **Comparison (P0-19)** | `/#compare` loads with table visible |
| **FAQ expansion (P0-20)** | `/#faq` — count 13 `<details>` entries |
| **Stripe prod default (P0-25)** | `curl dev.executespec.ai/api/billing/plans` shows USD plans |

To re-verify a single item by command line:

```bash
# Example: re-verify P0-5 (BingSiteAuth)
curl -s https://executespec.ai/BingSiteAuth.xml | grep 9119F5F20E74823DD54C550A8D40B0D4

# Example: re-verify P0-6 (description length)
curl -s https://executespec.ai/ | grep -m1 'name="description"' \
  | awk -F'content="' '{print $2}' | awk -F'">' '{print $1}' \
  | tr -d '\n' | wc -c
# Expect: 144

# Example: re-verify P0-7 (title length)
curl -s https://executespec.ai/ | grep -oE '<title>[^<]*</title>' \
  | sed 's|<title>||; s|</title>||' | tr -d '\n' | wc -c
# Expect: 59
```
