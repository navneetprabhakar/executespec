# Website refactor + benchmarks page

> **Status:** PLAN — written 2026-06-11, companion to [`ui-ux-review-and-recommendations-2026-06-11.md`](ui-ux-review-and-recommendations-2026-06-11.md) (§3.1 covers the marketing site's copy/CTA surface; this doc covers the **folder structure, deploy hygiene, and the new benchmarks page**).
> **Trigger:** benchmark results are about to be published on the site. The current single-file structure has no place to put them and no safe way to update them.
> **Scope:** `website/` only (the Cloudflare Worker static site bound to `executespec.ai` + `www`). The app frontend is out of scope.

---

## 0. Current state (verified 2026-06-11)

```
website/                       8.3 MB total
├── index.html                 1,677 lines — the ENTIRE site
│   ├── lines 1–472    head: meta + 7 JSON-LD blocks + GA + font links
│   ├── lines 473–1223 ~750 lines of inline CSS in one <style> block
│   └── lines 1224–1677 body sections + 1 inline behavior <script>
├── llms.txt · robots.txt · sitemap.xml · site.webmanifest · BingSiteAuth.xml
├── wrangler.toml              Worker "square-bar-e1dc", static assets, manual deploy
├── .assetsignore              correctly excludes internal docs + press-kit ✓
├── tier-decision.md · pricing-analysis.md · product-hunt-launch.md · ux-review.md   ← internal strategy docs living in the deployable folder (deploy-ignored, but misplaced)
├── press-kit/                 screenshots + og-hero template (deploy-ignored)
├── images/  logo.png 375 KB · logo_with_name.png 407 KB · og-hero.png 230 KB · navneet.jpg 101 KB
└── media/   hero.webm 3.6 MB
```

Deploy: `npx wrangler deploy --config website/wrangler.toml` — manual, no CI. `.wrangler/` is gitignored ✓. No `404.html` (despite `not_found_handling = "404-page"`), no `_headers`, no `_redirects`.

## 1. Problems, ranked

| ID | Sev | Problem | Evidence |
| --- | --- | --- | --- |
| W1 | **P0** | **sitemap.xml is poisoned**: it submits `dev.executespec.ai` URLs to search engines (`/landing`, `/exec-demo`, `/team`, `/library`, `/migrations`, `/trust`, `/pricing` on **dev**) — indexing the dev environment — plus legacy routes the app now redirects, plus `#fragment` URLs that crawlers ignore | `website/sitemap.xml` |
| W2 | P1 | 1,677-line monolith: every copy tweak risks the whole page; CSS/JS/SEO/content are one file; a second page (benchmarks) is impossible without copy-paste divergence | `index.html` |
| W3 | P1 | **No benchmarks surface and no data-driven update path** — results would be hand-edited into HTML, the exact pattern that caused the pricing-truth drift (RL-2026-06-019, and TIER-1 in the companion review) | — |
| W4 | P2 | Internal strategy docs live in the deploy folder. Safe today only because `.assetsignore` lists each by name — a new `*.md` dropped here ships publicly by default | `website/*.md` |
| W5 | P2 | Media weight: 375/407 KB PNG logos (should be SVG/WebP ≤20 KB), og-hero 230 KB (>200 KB social-crawler guidance), hero.webm 3.6 MB with no poster/lazy strategy visible | `images/`, `media/` |
| W6 | P2 | No `404.html` although wrangler is configured for one; unknown URLs fall back silently | `wrangler.toml` |
| W7 | P2 | No `_headers`: no caching policy (hashed-asset long-cache vs HTML no-cache), no security headers on the marketing origin | — |
| W8 | P2 | Deploy is manual-only — results updates will want a push-to-deploy loop | `wrangler.toml` comment |
| W9 | P3 | `meta name="keywords"` is a ~1,900-char stuffing block (ignored by Google since 2009; reads as spam to reviewers). The competitor-alternatives positioning already lives in `llms.txt` + JSON-LD where it actually works | `index.html:24` |
| W10 | P3 | Render-blocking Google Fonts stylesheet (Space Grotesk + JetBrains Mono) | `index.html:465–471` |

## 2. Target structure (zero-build, multi-page)

**Decision: stay zero-build.** The site's deploy story (one `wrangler deploy`, no toolchain) is a feature for a solo operator. Going multi-page with shared assets fixes W2/W3 without adding a build. Re-evaluate a static-site generator (Astro/Eleventy) only if the site exceeds ~5 pages or header/footer duplication starts hurting — that is Phase 3, explicitly optional.

```
website/
├── index.html                 slimmed to: head (trimmed) + sections + footer
├── benchmarks/
│   └── index.html             NEW — results page (spec in §3)
├── 404.html                   NEW — brand 404 with nav back to / + /benchmarks
├── assets/
│   ├── site.css               extracted from index.html <style> (lines 473–1223), shared by all pages
│   ├── site.js                extracted behavior script (line 1662+)
│   └── benchmarks.js          ~60 lines vanilla JS: fetch + render data/benchmarks.json
├── data/
│   └── benchmarks.json        ★ single source of truth for results — updating the site = editing this file
├── images/                    logo.svg (replaces both PNGs) · og-hero.png re-exported ≤200 KB · navneet.jpg
├── media/                     hero.webm + hero-poster.jpg (poster + preload="none")
├── _headers                   NEW — cache + security headers (template in §4)
├── robots.txt · site.webmanifest · BingSiteAuth.xml
├── sitemap.xml                REWRITTEN: https://executespec.ai/ + /benchmarks/ + /llms.txt only
├── llms.txt                   + a "Benchmarks" section mirroring published numbers
├── wrangler.toml · .assetsignore
└── (press-kit stays, deploy-ignored — see Decision D2)
```

**Moves out of the folder:** `tier-decision.md`, `pricing-analysis.md`, `product-hunt-launch.md`, `ux-review.md` → `docs/marketing/` (git mv; update any links; shrink `.assetsignore` accordingly). The deployable folder should contain only deployables + the two deploy-control files.

**Inside index.html:** drop the keywords meta (W9); keep the 7 JSON-LD blocks (they earn their bytes) but add `BreadcrumbList`/`WebPage` entries for `/benchmarks/`; fonts get `media="print" onload` swap or `font-display: swap` self-host (W10).

## 3. The benchmarks page (build now, fill when results arrive)

### 3.1 Data contract — `website/data/benchmarks.json`

```json
{
  "updated": "2026-06-11",
  "suites": [
    {
      "id": "terminal-bench-2.0",
      "name": "Terminal-Bench 2.0",
      "harness": "Harbor (official)",
      "mode": "blind",
      "publishable": true,
      "slice": "first 20 tasks",
      "model": "DeepSeek V4 (native)",
      "provider": "deepseek",
      "resolved": 12,
      "total": 20,
      "cost_total_usd": 0.40,
      "wall_clock": "47m 52s",
      "run_date": "2026-06-05",
      "baseline": {"label": "single-shot baseline", "resolved": 7, "total": 20},
      "notes": "ExecuteSpec's production WorkerAgent, remote-tool mode; no task-specific prompts.",
      "evidence_url": null,
      "leaderboard_url": null
    }
  ]
}
```

Rules the renderer enforces (this is the anti-drift mechanism):

1. **`publishable: false` or `mode: "oracle"` entries never render.** The discarded oracle-mode SWE-bench numbers (67%/60%, PR #88 era) must not reappear; SWE-bench stays absent until the rebuilt blind harness produces final numbers (`eval_methodology_rebuild`).
2. Every card renders `mode`, `slice`, `model`, and `run_date` — **no naked percentages**. A "12/20 on the first-20 slice" claim is defensible; "60% on Terminal-Bench" alone is not.
3. `updated` renders in the page header and in a `dateModified` JSON-LD field.
4. Derived numbers (resolved %, cost-per-resolved) are computed by the renderer, never hand-entered — one fewer thing to contradict.

### 3.2 Page sections (in order)

1. **Hero**: one verified headline claim + sub-line "Every number below is from a blind run of our production pipeline — the same code path customers run." (This claim is true and is the differentiator per the benchmarks-run-the-real-system rule; say it explicitly.)
2. **Result cards** (one per suite): name + mode badge (`BLIND`), resolved bar `N/M (xx%)`, model, total cost + cost-per-resolved, wall-clock, slice, run date, baseline delta when present ("single-shot 7/20 → pipeline 12/20"), evidence/leaderboard links when set.
3. **Methodology**: what *blind* means here (issue-only spec; hidden tests applied externally at scoring); what the harness does and doesn't see; link to the harness design doc on GitHub if/when public.
4. **What we don't publish**: a short honesty block — "We discard oracle-mode results (test-in-prompt). Suites in re-measurement are listed as *pending*, not estimated." Render `publishable:false` suites as a name-only "in progress" row if `show_pending: true`.
5. **CTA**: "Run it on your own repo" → `/register` (+ secondary "Read the methodology").
6. **JSON-LD**: `Dataset` schema (name, dateModified, distribution → the JSON file URL, license) + `WebPage`.

### 3.3 Rendering

`benchmarks.js`: `fetch('/data/benchmarks.json')` → filter `publishable && mode === 'blind'` → render cards into `<main data-benchmarks>` with a `<noscript>` fallback line linking the raw JSON. Zero dependencies, ~60 lines. (If SEO of the numbers themselves matters later, Phase 3's SSG inlines the same JSON at build time — the data contract doesn't change.)

## 4. Hygiene fixes (small, concrete)

- **sitemap.xml (W1)** — full replacement:
  ```xml
  <urlset>
    <url><loc>https://executespec.ai/</loc></url>
    <url><loc>https://executespec.ai/benchmarks/</loc></url>
    <url><loc>https://executespec.ai/llms.txt</loc></url>
  </urlset>
  ```
  Nothing on `dev.executespec.ai` belongs in any sitemap; separately, dev should send `X-Robots-Tag: noindex` (one Caddy/Cloudflare rule on the dev host — outside this folder, flagging it here because W1 proves dev is already being submitted for indexing).
- **404.html (W6)**: minimal branded page, links to `/` + `/benchmarks/` + app sign-in.
- **`_headers` (W7)** starter:
  ```
  /assets/*
    Cache-Control: public, max-age=31536000, immutable
  /data/*
    Cache-Control: public, max-age=300
  /*
    X-Frame-Options: DENY
    X-Content-Type-Options: nosniff
    Referrer-Policy: strict-origin-when-cross-origin
  ```
  (HTML gets Cloudflare's default revalidation; asset filenames change when content changes — with zero-build, suffix manually e.g. `site.v2.css`, or accept `max-age=3600` on assets.)
- **Media (W5)**: re-export the logo as SVG (one asset replaces both PNGs); og-hero re-export ≤200 KB; `<video poster="media/hero-poster.jpg" preload="none" loading="lazy">`.
- **Fonts (W10)**: add `&text=` subsetting or self-host the two families with `font-display: swap`.

## 5. Deploy automation (W8)

`.github/workflows/website.yml`: on push to `main` with `paths: website/**` → `npx wrangler deploy --config website/wrangler.toml` using a `CLOUDFLARE_API_TOKEN` repo secret (Workers-deploy scope, this Worker only). Keep manual deploy as the fallback. *Needs the token minted + added to repo secrets — operator step.*

## 6. Phased execution

| Phase | Contents | Effort |
| --- | --- | --- |
| **0 — hygiene (do immediately, no restructuring)** | W1 sitemap rewrite · W6 404.html · W4 move the 4 internal docs to `docs/marketing/` · W9 drop keywords meta | ~half day |
| **1 — split + benchmarks (the main event)** | Extract `assets/site.css` + `site.js` from index.html · create `/benchmarks/` page + `data/benchmarks.json` + `benchmarks.js` per §3 · llms.txt benchmarks section · JSON-LD additions · nav link "Benchmarks" on index | ~1–1.5 days |
| **2 — polish** | W5 media diet · W7 `_headers` · W10 fonts · §5 CI deploy | ~half day |
| **3 — optional, only if page count grows** | Eleventy/Astro adoption (header/footer templating, build-time JSON inlining); same data contract | not scheduled |

**Acceptance:** Phase 0 — Search Console accepts the new sitemap; no `dev.` URL submitted; internal docs gone from the deploy folder. Phase 1 — publishing a new benchmark result = a one-line PR to `data/benchmarks.json`; `/benchmarks/` renders cards with mode/slice/date on every number; oracle or unpublishable entries cannot render. Phase 2 — Lighthouse perf ≥90 on `/` mobile; og-hero ≤200 KB.

## 7. Decisions needed (operator)

- **D1 — SWE-bench presence**: show as "in re-measurement (pending)" on the page, or omit entirely until the rebuilt-harness numbers land? (Plan defaults to *pending row, no numbers*.)
- **D2 — press-kit**: it's currently deploy-ignored. Press kits are normally public — publish at `/press/` (it's already curated screenshots + README), or keep private until launch?
- **D3 — CI deploy**: approve minting the Cloudflare API token for the workflow, or keep deploys manual?
- **D4 — Terminal-Bench framing**: 12/20 is on the first-20 slice. Publish now with the slice caveat (§3.1 rule 2 makes it honest), or wait for the full-89 sweep (the `benchmark` branch's full-89 TB driver) and publish that instead? Plan accommodates either — it's a JSON entry.

---

**When the benchmark results are shared:** they go into `data/benchmarks.json` per the §3.1 contract (one object per suite/run), and the page renders them — no HTML edits. If a result needs a field the contract lacks, extend the contract in this doc first, then the renderer.
