# 02 — Tech stack: honest assessment

> Sources: pom.xml, frontend/package.json, application*.yml, build configs (all read directly);
> per-package library usage from batch checkpoints. Verdicts are mine.

## 1. Backend — Java 21 + Spring Boot 3.5.14 + Spring AI 1.1.6

**Verdict: right foundation, used unevenly.**

- **Java 21 + Boot 3.5**: current, boring, correct. Virtual threads enabled only under the `gcp`
  profile (yml:784-786) — local/dev runs platform threads, so thread-pool bugs (the
  PipelineExecutorPool starvation risk) behave differently in dev than prod. Pick one.
- **Spring AI is a half-dependency.** Only Anthropic actually flows through Spring AI's
  ChatModel; OpenAI/Gemini/DeepSeek/OpenRouter/Fireworks ride hand-rolled native clients
  (OpenAiNativeToolClient/GeminiNativeToolClient). What you really use from the framework:
  auto-config for one provider, the retry block, and the Usage type — which RunCostTracker then
  reads **by reflection** for cache fields (silently breaks on upgrade; no canary test). Either
  commit to Spring AI (unlikely to keep pace with native tool/caching features you need) or
  demote it to "Anthropic SDK + config" and own all native clients uniformly — the codebase has
  already voted for the latter with its feet. The doc-10 transport layer is the natural moment.
- **Monolith, single JAR, frontend in static/**: correct for this team size. Don't split.
- **PostgreSQL + pgvector + Flyway (236 migrations) + Redis**: solid. Two notes: (a) JSON-blob
  columns are doing entity work in places (issues_json shape drift already caused BUG-3 in doc 09);
  (b) six write-only tables found (checkpoints/be-state-exec.md) — schema is outrunning the product.
- **JGit 6.9 (2024-era)**: a major behind (7.x); fine functionally, flag for the dependency pass.
- **Build**: Spotless(google-java-format) + JaCoCo + Surefire excluding e2e/integration/chaos
  groups. Sane. **Spring milestone + snapshot repos are still configured (pom:525-542) while no
  dependency needs them — supply-chain surface for zero benefit; delete.** Mockito/ByteBuddy
  overrides commented "for Java 25" while java.version=21 — stale comment or premature pin.

## 2. Frontend — React 18 + Vite 5 + TS 5.3 + Tailwind 4 + Zustand 4

**Verdict: pragmatic and fast to build in; aging pins + zero enforced quality gates.**

- React 18 / Vite 5 / Tailwind 4: fine. TypeScript 5.3, ESLint 8 (legacy config), Zustand 4,
  react-markdown 8, react-router 6 — each one major behind; none urgent, all cheap to bump now
  vs expensive later.
- **The lint gate is decorative**: `--max-warnings 9999` means CI enforces prettier only.
- **Vitest exists with test scripts and jsdom** — but only a token number of test files exist.
  The infrastructure-vs-usage gap is the frontend's biggest debt (details in 05/checkpoints).
- **No code-splitting**: no manualChunks, no React.lazy — single ~800KB bundle carrying d3 AND
  recharts (two chart libs), framer-motion, highlight.js AND a hand-rolled tokenizer,
  @monaco-editor/react (usage questionable — see 03), OTel web auto-instrumentation. For a
  dev-tool dashboard this is lazy-loadable down to a fraction.
- **PWA bits** (manifest + sw.js) present — verify the service worker isn't caching stale app
  shells against an API that changes weekly (checkpoints/fe-lib-stores.md).

## 3. Infra — GCP us-east4, single VM prod+dev, Cloud Run previews, Cloudflare

**Verdict: appropriately small; the risky part is everything implicit.**

- Single VM serving prod+dev with profile switches is acceptable at one-customer scale, with two
  caveats: the JWT secret has a hardcoded dev default in tracked YAML (yml:631 — fine ONLY if
  prod always overrides; the profile YAMLs don't show it, so it rides env vars — verify), and
  `preview.engine.api-key` defaults to `change-me-in-prod` (yml:576).
- Observability stack (Micrometer+OTel→Prometheus/Tempo/Grafana local, Cloud Trace on GCP) is
  genuinely good for this stage — better than most seed-stage products.
- CI: 4 of 5 workflows currently disabled (deliberate, benchmark-era). The test workflow runs
  unit-only; integration/e2e/chaos tagged groups exist but nothing runs them on a cadence —
  which is exactly how the proxy-self-invocation bug family survived (their tests would need a
  Spring context).
- Terraform manages the (now torn-down) benchmark env; prod/dev infra is hand-managed — fine,
  but write the runbook down once the docs rebuild happens.

## 4. The three stack-level calls I'd make

1. **Own the LLM client layer explicitly** (doc 10 transports): one OpenAI-compatible client
   parameterized by base-url/wire-rules + the two genuinely different clients (Anthropic, Gemini).
   Spring AI demoted to Anthropic transport internals or dropped.
2. **Make the quality gates real**: eslint max-warnings=0 on changed files, vitest in CI, one
   Spring-context smoke test profile that would catch proxy/config breakage, Surefire integration
   job nightly.
3. **Dependency hygiene pass** (one afternoon): drop milestone/snapshot repos, kill the unused
   chart/editor lib (after fe-lib confirmation), bump the one-major-behind set, add Renovate-style
   grouped updates — Dependabot already runs; the gap is removal, not addition.
