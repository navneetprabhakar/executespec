# Readymade Solutions Library — pending plan

> **Status:** PENDING (scope approved 2026-04-29; not yet started).
> **Branch when work begins:** `feature/v3-platform-expansion` (or
> a new branch off it; TBD at execution time).
> **R-### range reserved:** R-327 → R-335 (one per wave below;
> may overflow as the catalog seeding work spans multiple commits).
> **Author:** 2026-04-29 thread, after competitor analysis +
> codebase audit + 6-question scope discussion.

## One-line pitch

> *"A searchable library of standard building blocks — sign-in,
> payments, notifications, data, monitoring, AI — that adapts to
> your repo's stack, ships as a PR you own. Free starters for
> everyone, premium catalog for paid tiers."*

## What this is

A **library of pluggable, searchable solutions** that customers
pick from to ship standard features fast. Each solution:

- Declares **language** + **framework** + **provider** variants
  (e.g. Login-with-Google has TS-Express, TS-Fastify, Python-FastAPI,
  Java-Spring-Boot, Go-Echo variants — each with its own spec
  template + scaffold).
- Reads the user's **codemap + detected stack** at runtime and
  resolves to the right variant automatically. If the user's repo
  is Express+TS+Mongoose, the user sees the Express+TS+Mongoose
  variant of the solution; same card, different generated code.
- Has **slots** (parameters the user fills before launch — e.g.
  Stripe checkout needs `currency`, `success URL`, `which page to
  add the button to`).
- Declares **dependencies** on other solutions (Login-with-Google
  depends on User-entity → if the repo doesn't have one, the
  library auto-installs both as a stack).
- Composes into **stacks** (e.g. "SaaS auth+billing starter" =
  Email auth + Google login + Stripe subs + User+Org entities).
- Ships output as a **PR** against the user's chosen repo,
  consistent with our existing pipeline.

## Scope decisions (decided 2026-04-29)

| # | Question | Decision |
|---|----------|----------|
| 1 | Catalog breadth for v1 | **Build all 15 categories**; user-friendly categorisation (see below). |
| 2 | First-party vs community | **Both** — single catalog with `source: FIRST_PARTY \| COMMUNITY` flag; community submissions go through a moderation pipeline. |
| 3 | Architecture-awareness depth | **L1 in v1** (stack-aware variant resolution: pick the right TS/Python/Go variant based on detected stack); **L2** (rewrite generated code to match the user's existing patterns) **deferred to v1.5+**. |
| 4 | Migrations as a product | **Separate product** — migrations (Express→Fastify, JS→TS, Mongoose→Prisma, etc.) get their own plan doc + product surface. NOT included in this library. See [pending-migrations-plan.md](pending-migrations-plan.md). |
| 5 | Stacks vs primitives | **Both in v1** — no end-user separation; the library treats single-solution primitives and multi-solution stacks the same way. Stacks declare their member primitives via the dependency graph. |
| 6 | Tier gating | **Visible-but-locked.** FREE users see the entire library; lightweight starter solutions are unlocked, the rest render with a lock icon + upgrade CTA. PRO/TEAM/ENTERPRISE see the full catalog unlocked. |
| 7 | **Language as a parameter** | First-class — every solution declares a list of supported languages; the variant resolution picks language × framework × provider per repo. |
| 8 | **Tier-stratified cost model** *(added 2026-04-29 — applies to both Readymades AND Migrations)* | **FREE/PRO** = managed-key + credit-charged → cost caps mandatory + enforced. **TEAM/ENTERPRISE** = BYOK + fixed subscription → cost caps info-only (we surface estimated cost but don't enforce; LLM bill goes direct from provider to customer). See dedicated section below. |

## Tier-stratified cost model (cross-product)

Added 2026-04-29 — applies to **both** this product and Migrations:

- **FREE / PRO tiers — managed-key, credit-charged:**
  ExecuteSpec pays the LLM provider; user pays per-credit.
  **Cost caps are mandatory.** Card chips show "X credits"; detail
  page enforces a per-launch budget; runaway runs auto-abort on cap
  hit.
- **TEAM / ENTERPRISE tiers — BYOK, fixed-subscription:**
  User brings their own LLM keys; pays a fixed monthly subscription
  for ExecuteSpec the **service** (not per-credit). LLM cost is on
  them, billed direct by Anthropic / OpenAI / Google.
  **Cost caps are info-only** — we surface estimated cost so they
  can budget against their LLM provider, but we don't enforce.

Implementation:

- `TierEnforcementService.requiresCostCap(tier)` predicate (true
  for FREE/PRO, false for TEAM/ENTERPRISE).
- `V2RunCostTracker` tracks cost regardless (for analytics +
  recipe-cost calibration); the cap-enforcement gate fires only
  when `requiresCostCap` is true.
- UI: cost-cap input on detail page rendered conditionally. For
  TEAM/ENTERPRISE: read-only "Est. $X on your keys" chip.
- BYOK key usage: `ApiKeyResolver` already picks user-keys for
  TEAM/ENTERPRISE — no new wiring there.

This wires into the readymades flow at **Wave 4 (frontend gallery
+ detail page)** and **Wave 5 (slot-filling + spec composition)**;
the Migrations plan does the equivalent at its W6.

## User-friendly categorisation (15 categories)

Renamed for end-user clarity. Each card carries a category badge.
Internal mapping to the original 16-category brainstorm in
parens.

| Display category | Examples |
|---|---|
| **Sign-in & users** *(Auth & Identity)* | Email+password, Google login, GitHub login, Apple login, magic link, 2FA, passkeys, RBAC, SSO/SAML, user invitations |
| **Take payments** *(Payments & Billing)* | Subscriptions (Stripe / Razorpay / Paddle), one-time checkout, marketplace payouts, usage-based billing, coupons, refunds, tax, invoicing, GST/regional compliance |
| **Send messages** *(Notifications)* | Transactional email (Resend / Sendgrid / Postmark / SES), email templates, SMS (Twilio / Msg91), web push, mobile push, in-app inbox, Slack notifications, outbound webhooks |
| **Store data** *(Data layer)* | Postgres + migrations (Prisma / Drizzle / Flyway / Alembic), MongoDB, Redis cache, background jobs (BullMQ / Sidekiq / Celery / Temporal), search (Meilisearch / Typesense / Algolia / Postgres FTS), file storage (S3 / R2 / GCS), multi-tenancy patterns |
| **Ship & monitor** *(DevOps & Infra)* | Docker, GitHub Actions CI, Kubernetes, Terraform, cloud deploy targets (Vercel / Fly / Render / Railway / Cloud Run / ECS), structured logging, distributed tracing, error reporting (Sentry / Bugsnag), cron jobs |
| **Build the UI** *(Frontend primitives)* | Component library (shadcn / Radix / Mantine / Chakra / MUI), forms, data tables, theme switcher, i18n, toast, modal, file uploader, markdown editor, charts, onboarding tour, settings page, admin panel, PWA, SEO, error pages |
| **Stay compliant** *(Compliance & Security)* | GDPR export/deletion, audit logs, cookie banner, privacy policy, ToS, rate limiting, CAPTCHA, CSP/security headers, secrets management, API key issuance |
| **Talk to users** *(Communication & Support)* | In-app chat (Stream / Talkjs), video calls (Daily / LiveKit), support widget (Intercom / Crisp), help centre (Docusaurus / Mintlify), real-time (websockets / Pusher / Ably), comments + threads, activity feed |
| **Add AI** *(AI integrations)* | LLM chat (OpenAI / Anthropic / Gemini / Groq / Ollama), embeddings + semantic search (OpenAI / Voyage / Cohere) + vector DB (pgvector / Pinecone / Weaviate / Qdrant / Chroma), RAG, image gen, speech-to-text, text-to-speech, streaming chat UI, tool calling, content moderation |
| **Track usage** *(Analytics & Telemetry)* | Product analytics (PostHog / Mixpanel / Amplitude), web analytics (Plausible / Umami / GA4), session replay, heatmaps, APM, A/B testing + feature flags (PostHog / GrowthBook / LaunchDarkly / Unleash / Statsig) |
| **Grow the user base** *(Marketing & Growth)* | Referral program, newsletter signup (Mailchimp / Beehiiv / Convertkit), email campaigns (Customer.io / Loops / Resend Broadcasts / Knock), status page, affiliate tracking, waitlist, coming-soon page |
| **Go mobile** *(Mobile)* | React Native / Expo shell, mobile auth, mobile push (FCM / APNS / Expo), deep linking, app store deploy (EAS / Fastlane) |
| **Location & maps** *(Maps & Location)* | Maps embed (Google / Mapbox / Leaflet / MapLibre), geocoding, address autocomplete |
| **Booking & scheduling** *(Scheduling & Booking)* | Calendar integration (Google / Outlook / iCal), booking flow, appointments / reservations |
| **Work with APIs** *(Developer Experience)* | OpenAPI / Swagger docs, testing framework setup, API SDK auto-gen, GraphQL, webhook handlers, contract testing |

The 15 categories also belong to **4 macro-groups** for top-of-page
faceting:

- **People & money** — Sign-in & users, Take payments, Send messages
- **Data & infra** — Store data, Ship & monitor, Stay compliant
- **UI & growth** — Build the UI, Talk to users, Track usage, Grow the user base
- **Smart & reach** — Add AI, Go mobile, Location & maps, Booking & scheduling, Work with APIs

## Schema sketch (V157 migration)

```sql
-- Solution = the abstract library entry. Variants live below.
CREATE TABLE readymade_solution (
  id           UUID PRIMARY KEY,
  slug         VARCHAR(64) UNIQUE NOT NULL,        -- "login-with-google"
  name         VARCHAR(160) NOT NULL,              -- "Login with Google"
  tagline      VARCHAR(255),                       -- card subtitle
  description  TEXT,                               -- detail page body
  category     VARCHAR(64) NOT NULL,               -- "sign-in-and-users"
  macro_group  VARCHAR(32) NOT NULL,               -- "people-and-money"
  tags         JSONB,                              -- ["auth","oauth","social"]
  kind         VARCHAR(16) NOT NULL,               -- PRIMITIVE | STACK | INTEGRATION
  tier         VARCHAR(16) NOT NULL DEFAULT 'PRO', -- FREE | PRO | TEAM | ENTERPRISE
  source       VARCHAR(16) NOT NULL DEFAULT 'FIRST_PARTY', -- FIRST_PARTY | COMMUNITY
  status       VARCHAR(16) NOT NULL DEFAULT 'LISTED',
  icon_url     TEXT,
  thumbnail_url TEXT,
  sample_pr_url TEXT,                              -- "this is what your PR will look like"
  est_files_low INT,                               -- card cost-transparency chip
  est_files_high INT,
  est_credits_low NUMERIC(10,2),
  est_credits_high NUMERIC(10,2),
  author_user_id VARCHAR(36),                      -- nullable (FIRST_PARTY = NULL)
  author_org_id  VARCHAR(36),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_readymade_category ON readymade_solution (category, status);
CREATE INDEX idx_readymade_tier     ON readymade_solution (tier, status);
CREATE INDEX idx_readymade_source   ON readymade_solution (source, status);
-- Full-text search index for the searchable library:
CREATE INDEX idx_readymade_fts ON readymade_solution USING GIN
  (to_tsvector('english', name || ' ' || coalesce(description,'') || ' ' || coalesce(tagline,'')));

-- Variant = the concrete (language, framework, db, provider) cell.
-- A single solution has many variants; runtime resolution picks one.
CREATE TABLE readymade_variant (
  id            UUID PRIMARY KEY,
  solution_id   UUID NOT NULL REFERENCES readymade_solution(id) ON DELETE CASCADE,
  language      VARCHAR(32) NOT NULL,              -- typescript / python / go / java / rust / ruby / php / csharp / kotlin / swift / dart
  framework     VARCHAR(64) NOT NULL,              -- express / fastify / nestjs / fastapi / django / spring-boot / actix / rails / laravel / dotnet
  options       JSONB,                             -- { provider:"google", db:"postgres", style:"server-action" }
  spec_template TEXT NOT NULL,                     -- the spec text with {{slot}} placeholders
  scaffold_key  VARCHAR(64),                       -- optional ref to prompts/scaffolds/
  est_credits_low  NUMERIC(10,2),                  -- per-variant cost; falls back to parent solution
  est_credits_high NUMERIC(10,2),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_readymade_variant_solution ON readymade_variant (solution_id);
CREATE INDEX idx_readymade_variant_stack    ON readymade_variant (language, framework);

-- Slot = a parameter the user fills before launch.
CREATE TABLE readymade_slot (
  id           UUID PRIMARY KEY,
  solution_id  UUID NOT NULL REFERENCES readymade_solution(id) ON DELETE CASCADE,
  name         VARCHAR(64) NOT NULL,               -- "currency"
  display_name VARCHAR(160) NOT NULL,              -- "Currency"
  type         VARCHAR(32) NOT NULL,               -- TEXT | SELECT | SECRET | REPO_PICKER | FILE_PATH | NUMBER | BOOLEAN
  required     BOOLEAN NOT NULL DEFAULT FALSE,
  default_value TEXT,
  options      JSONB,                              -- for SELECT: ["USD","EUR","INR","GBP"]
  description  TEXT,
  display_order INT NOT NULL DEFAULT 0,
  UNIQUE (solution_id, name)
);

-- Dependency = "this solution requires that one (auto-install if missing)"
CREATE TABLE readymade_dependency (
  solution_id     UUID NOT NULL REFERENCES readymade_solution(id) ON DELETE CASCADE,
  depends_on_id   UUID NOT NULL REFERENCES readymade_solution(id),
  required        BOOLEAN NOT NULL DEFAULT TRUE,    -- false = "recommended pairing"
  PRIMARY KEY (solution_id, depends_on_id)
);

-- Per-org "favourites" / "saved" so users can pin frequently used.
CREATE TABLE readymade_favourite (
  user_id      VARCHAR(36) NOT NULL,
  solution_id  UUID NOT NULL REFERENCES readymade_solution(id) ON DELETE CASCADE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, solution_id)
);
```

## Wave plan (9 waves; bigger than agent-builder)

### Wave 1 — Schema + entity (R-327)

- V157 migration (5 tables above).
- JPA entities: `ReadymadeSolution`, `ReadymadeVariant`,
  `ReadymadeSlot`, `ReadymadeDependency`, `ReadymadeFavourite`.
- Repositories + DTOs.
- **Tests:** entity round-trip + repository basic queries.

### Wave 2 — Catalog seeding infrastructure (R-328)

- New service `ReadymadeSeeder` reads YAML catalog files at boot
  + on-demand reseeding via admin endpoint.
- Catalog format spec at `docs/readymades-catalog-format.md` —
  the contract authors (us + community) follow.
- Seed format example:
  ```yaml
  slug: login-with-google
  name: Login with Google
  category: sign-in-and-users
  macro_group: people-and-money
  tier: FREE                   # this one is free-tier
  kind: PRIMITIVE
  tags: [auth, oauth, social]
  est_files: [3, 8]
  est_credits: [1, 2]
  slots:
    - { name: client_id, type: SECRET, required: true }
    - { name: client_secret, type: SECRET, required: true }
    - { name: callback_path, type: TEXT, default: /auth/google/callback }
  dependencies:
    - { slug: user-entity, required: true }
  variants:
    - { language: typescript, framework: express, spec_template: "..." }
    - { language: typescript, framework: fastify, spec_template: "..." }
    - { language: python, framework: fastapi, spec_template: "..." }
    - { language: java, framework: spring-boot, spec_template: "..." }
    # ... more
  ```
- **Tests:** seeder loads valid catalog; rejects invalid; idempotent on re-run.

### Wave 3 — Library API + variant resolver (R-329)

- New `library/` package — `ReadymadeController`, `ReadymadeService`,
  `VariantResolver`.
- Endpoints:
  - `GET /api/library` — list with filters (category, macro_group,
    tier, source, search, my-stack-only).
  - `GET /api/library/{slug}` — solution detail + variants + slots
    + dependencies.
  - `GET /api/library/categories` — facet counts.
  - `POST /api/library/{slug}/resolve?projectId=…&repoId=…` —
    pick the right variant for the user's repo (uses
    `ProjectArchitectureService` + `StackDetector`).
  - `POST /api/library/{slug}/instantiate` — submit the slot-filled
    spec to the V2 pipeline as a normal run; persist the chosen
    `solutionId` + `variantId` on the run.
  - `POST /api/library/favourite/{slug}`, `DELETE /api/library/favourite/{slug}`.
- Tier-gating logic: `library.list()` returns all solutions
  visible to the caller's tier; locked items get `is_locked: true`
  + `unlock_tier: "PRO"` so the UI can render the lock icon.
- **Tests:** stack-aware variant resolution + tier-gating logic +
  search + dependency walk.

### Wave 4 — Frontend gallery + detail page (R-330)

- New route `/v3/library` — gallery with:
  - **4 macro-group tabs** at the top.
  - **15 category facet rail** on the left.
  - **Free-text search** with debounce + FTS hits.
  - **"Only show what works with my stack"** toggle (default ON
    when the user has a project; OFF when they don't).
  - Card grid: name, category badge, tier chip (lock icon when
    locked), files-touched range, credits range, favourite toggle.
- New route `/v3/library/:slug` — detail page with:
  - Hero banner + description.
  - Variant resolver result chip ("This will use the
    TypeScript+Express+Mongoose variant for your repo").
  - Slots form (each slot rendered per its `type`).
  - Dependency list ("Will also install: User entity").
  - Sample PR link (when present).
  - "Use this" CTA → `/api/library/{slug}/instantiate`.
- Lock state: clicking a locked card shows an upgrade modal
  (existing tier-upgrade flow).

### Wave 5 — Slot-filling + spec composition (R-331)

- `SlotValidator` per slot type (TEXT regex, SELECT membership,
  SECRET non-empty + length, REPO_PICKER FK validity, FILE_PATH
  workspace-rooted, NUMBER bounds, BOOLEAN coercion).
- `SpecComposer` — substitutes slot values into the variant's
  `spec_template` (Mustache-style `{{slot_name}}`) → produces the
  final spec text submitted to the pipeline.
- Pipeline integration:
  - `RunRequest` gains optional `readymadeSolutionId` +
    `readymadeVariantId` fields (V158: `pipeline_run.readymade_*`
    columns + `execution_run.readymade_*` columns).
  - Run terminal hook persists "this run instantiated readymade
    X variant Y" so the cost-tracker can update the est_credits
    population for future card displays.
- **Tests:** slot validation + composition + dependency expansion
  (e.g. picking a solution that needs User-entity auto-installs it).

### Wave 6 — Catalog seed (R-332..R-332+N — multi-commit)

- This is the **bulk of the actual work.** Authoring 130+
  solutions × multiple language/framework variants each is a
  multi-month effort and should be done as an ongoing series of
  commits (R-332.1, R-332.2, ...) NOT a single mega-commit.
- Suggested seed order (by user value × authoring effort):
  1. **Sign-in & users — top 10** (Email+password, Google login,
     GitHub login, Apple login, magic link, 2FA, RBAC, User+Org
     entity, password reset, email verification).
  2. **Take payments — top 8** (Stripe subs, Razorpay subs, Stripe
     one-time, Razorpay one-time, Paddle subs, coupons, refunds UI,
     payment webhooks).
  3. **Send messages — top 5** (Resend transactional, Sendgrid
     transactional, email templates, Twilio SMS, in-app inbox).
  4. **Store data — top 8** (Postgres+Prisma, Postgres+Drizzle,
     MongoDB+Mongoose, Redis cache, BullMQ, Meilisearch, S3 file
     storage, multi-tenancy).
  5. **Ship & monitor — top 6** (Docker, GitHub Actions CI,
     Vercel deploy, Fly.io deploy, Sentry, structured logging).
  6. **Add AI — top 6** (OpenAI chat, Anthropic chat, embeddings
     + pgvector, RAG, streaming chat UI, content moderation).
  7. **Build the UI — top 8** (shadcn/ui setup, Tanstack table,
     theme switcher, react-hook-form + zod, Tailwind setup,
     i18n, modal primitives, charts).
  8. **Stay compliant — top 5** (rate limiting, CAPTCHA, GDPR
     export/delete, audit logs, secrets management).
  9. Remaining categories: top 5-8 each.
- Quality bar: every solution must have at least 3 variants
  shipped (typescript-express, typescript-nextjs, python-fastapi
  is a reasonable minimum slate), a sample PR URL pointing to a
  real demo run, slots reviewed by a human author.
- **Stack solutions** (W6 sub-batch): authoring 5-10 multi-
  primitive stacks ("SaaS auth+billing starter", "Internal admin
  panel", "AI chat app starter", "Marketplace MVP"). Each stack
  is a row in `readymade_solution` with `kind=STACK` whose
  `dependency` rows enumerate the member primitives.

### Wave 7 — Community contribution path (R-333)

- New endpoints:
  - `POST /api/library/contribute` — submit a YAML/JSON catalog
    entry (any tier).
  - `GET /api/library/contributions/me` — list my submissions +
    moderation status.
  - `POST /api/admin/library/contributions/{id}/{approve|reject}`
    — SUPER_ADMIN moderation action.
- New table `readymade_contribution` — staging area before a
  submission gets promoted into `readymade_solution`.
- Moderation states: NEW → REVIEW → APPROVED → LISTED, with
  REJECTED + WITHDRAWN terminal states.
- Author profile fields populate `author_user_id` / `author_org_id`
  on the promoted solution row.
- Tier gating: COMMUNITY-source solutions are TIER=PRO by default
  (paid users only) until SUPER_ADMIN flips to FREE.

### Wave 8 — Tier-gating UI polish + lock-state UX (R-334)

- Lock-icon affordance + hover tooltip ("Upgrade to PRO to use
  this solution").
- Unlock CTA flows to existing `/v3/pricing` upgrade page with
  a `?from=library&solution=<slug>` deep-link so the user lands
  back on the library after upgrading.
- "Browse FREE solutions" filter shortcut for FREE-tier users.
- Per-card "files / credits" cost-transparency chips visible
  even on locked cards.

### Wave 9 — Search + recommendations (R-335)

- Full-text search wired to the `idx_readymade_fts` GIN index.
- "Trending" sort: count instantiations per `solution_id` per
  rolling 30 days.
- "Recently used" sort: per-user (or per-org) instantiation
  history.
- "Recommended for me": filter to solutions whose `language` +
  `framework` variants match the user's most-recent project's
  detected stack.
- Empty-state when "show only what works with my stack" returns
  zero hits: surface "No solutions for `<framework>` yet — see
  all solutions" escape hatch.

## Out of scope (v1) — explicit list

| Deferred to | Item |
|---|---|
| Separate product | **Migrations / Refactors** (Express→Fastify, JS→TS, Mongoose→Prisma, REST→tRPC, etc.) — qualitatively different (transforms existing vs adds new); will get its own plan doc. |
| v1.5 / v2 | **L2 architecture-awareness** — rewriting generated code to match user's existing patterns (functional vs class components, naming conventions, file structure). LLM-time adaptation pass; expensive to ship right. |
| v1.5 / v2 | **Real-time collaborative library editing** for community-contributed solutions. |
| v1.5 / v2 | **Plan-preview before submit** — show planner outline + cost on the detail page before the user spends a credit. Beautiful but expensive (per-Readymade-per-stack signature caching is non-trivial). |
| v2 | **Cross-org sharing** — org A's private community submission visible to org B. |
| v2 | **Custom variant authoring inside the UI** — today, variants are seeded from YAML; no in-product authoring tool. |
| v2 | **Versioning + rollback** of solution definitions. |
| Roadmap | **Public marketplace** with revenue share for community authors. |
| Roadmap | **Multi-language UI** (the LIBRARY content is multi-language by virtue of solution variants; the UI surface is English-only in v1). |

## Effort + cadence

- **9 waves**, but Wave 6 (catalog seed) is itself a multi-commit
  ongoing effort — 130+ solutions × 3-5 variants each.
- Plumbing (Waves 1-5, 7-9) is roughly **8-10 commits, ~4-6 weeks
  of focused work**.
- Catalog seed is **1-2 quarters of authoring effort** if done
  with a quality bar; can accelerate by accepting community
  submissions early.
- **L** total — bigger than agent-builder; more like the v2.6.3
  enterprise readiness arc in scale.

## Cross-references

- Codebase: `frontend/src/v3/pages/V3Landing.tsx` SAMPLE_PROMPTS,
  `project/template/ProjectSpecTemplate.java` (V102),
  `prompts/scaffolds/`, `ProjectArchitectureService`,
  `StackDetector`.
- Schema: V102 (existing project_spec_template — kept separate
  intentionally; user-saved templates are different from
  platform-curated solutions).
- Industry context: SKILL.md open standard adopted Dec 2025
  (relevant for solution seed format compatibility).
- Related plans:
  - [pending-agent-builder-plan.md](pending-agent-builder-plan.md) —
    PARKED 2026-04-29 pending product-shape rethink. Should be
    explicitly scoped as "user-built drag-and-drop agents," not
    "internal worker customisation."
  - **TBD: pending-migrations-plan.md** — separate product per
    decision #4; not yet authored.

## Open questions for execution-time (NOT blockers)

- **Catalog seed file structure on disk.** Single `catalog.yml`
  with all 130+ entries vs one file per solution at
  `resources/readymades/<slug>.yml`? Lean toward per-file for
  diff hygiene + community contribution friendliness.
- **Variant naming convention.** `(language, framework, options)`
  is enough to disambiguate, but how do we display it on cards?
  "TypeScript · Express · PostgreSQL" reads well; longer combos
  get unwieldy. Drop `options` from card display, surface only
  on detail page?
- **L1 stack-detection fallback.** When `StackDetector` returns
  ambiguous results (repo has both Express AND NestJS in package
  deps), how does `VariantResolver` choose? Probably surface a
  picker on the detail page; default to most-files-detected.
- **Free-tier solution count.** "A few lightweight ones" needs a
  concrete number. Lean: 8-12 solutions across Sign-in (3) +
  Send messages (1) + Build the UI (4) + Stay compliant (1) +
  Track usage (1) + Add AI (1-2 — limited rate). Operationalize
  by setting `tier=FREE` on those rows during seed.
- **Cost-transparency band sourcing.** v1 ships with hand-coded
  bands per variant; once 100+ runs land per solution, swap to
  historical p25/p75 from `RunCostLog`. The schema already
  supports both (estimates as columns; runs join on solution_id +
  variant_id).
- **Community submission tier-default.** PRO by default vs FREE
  by default? Lean PRO so the FREE catalog stays curated.
