# Full-repo review — progress tracker

> Started 2026-06-10. Branch: `benchmark` (working tree INCLUDES uncommitted Fireworks + mimo changes — reviewed as-is).
> Rule: NO inputs from docs/*.md or CLAUDE.md files — code/config only. Runtime prompts (src/main/resources/prompts/) ARE in scope.
> This file is the resume point if context is compacted. Each batch agent writes its raw findings to review/checkpoints/<batch>.md.

## Deliverables (final, in review/)
- 01-executive-summary.md
- 02-tech-stack.md
- 03-libraries-dependencies.md
- 04-code-review-backend.md
- 05-code-review-frontend.md
- 06-dead-code-cleanup.md
- 07-product-and-positioning.md
- 08-models-strategy.md
- 09-model-matrix-consistency-plan.md
- 10-aggregator-architecture-openrouter-fireworks.md
- 11-ux-review.md
- 12-terminal-bench-2-results.md (after TB sweep)
- README.md (index)

## Inventory (measured 2026-06-10)
- Backend: 1,111 java files / 168,145 LOC across 75 packages. Tests: 210 files / 34,154 LOC.
- Frontend: ide 17.4K, signal 14.4K, lib 5.7K, stores+hooks+types+console+design-system ~3.3K LOC.
- application.yml 799 lines; 236 migrations; 92 prompt files; 5 application*.yml profiles.
- Biggest packages: deployment 17.2K, auth 12.1K, integration 10.8K, billing 9K, agent 9K, tool 7.9K.

## Batch status (agent → checkpoint file)
| # | Batch | Scope | Status |
|---|-------|-------|--------|
| 1 | be-core-agents | agent/ + context/ + composer/ | DONE ✓ |
| 2 | be-orchestration | integration/ | DONE ✓ |
| 3 | be-worker-engine | engine/ + harness/ | DONE ✓ |
| 4 | be-tools-reviewer | tool/ + reviewer/ | DONE ✓ |
| 5 | be-router-cost | router/ + cost/ + scoring/ + report/ + smoketest/ + pricing/ | DONE ✓ (read by main; feeds 09/10 — WRITTEN) |
| 6 | be-state-exec | state/ + execution/ + run/ + recovery/ + sse/ + batch/ + saga/ | DONE ✓ |

### Relaunch note (post session interruption)
Wave-2/3 agents died with the interrupted session; be-project-intel completed + wrote its file first (READ ✓).
RELAUNCHED (2nd attempt, all background): be-billing, be-review-surfaces, be-governance, be-bench-eval,
be-deployment, be-api-platform, fe-ide, fe-signal-console, fe-lib-stores, infra-prompts, infra-build.
If interrupted again: check `ls review/checkpoints/*.md` vs the batch table; relaunch ONLY missing ones
(prompts reproducible from this file's batch scope table + the standard template).

### Synthesis state (main agent)
- WRITTEN: review/07-product-and-positioning.md, 08-models-strategy.md, 09-model-matrix-consistency-plan.md, 10-aggregator-architecture-openrouter-fireworks.md
- PENDING: 01 (exec summary, LAST), 02 (stack — draft in progress), 03 (libs), 04 (backend findings synthesis), 05 (frontend synthesis), 06 (dead code roll-up), 11 (UX synthesis after fe checkpoints), 12 (TB results after sweep), README
- Cross-cutting backend pattern found: Spring proxy self-invocation kills @Transactional/@Async/@Retryable in ≥3 packages (SequentialExecutor.persistTaskStatus, saga SideEffectExecutor, ExecutionOrchestrator) — systemic, goes in 01/04.
- Telegram: scope question sent, NO reply yet (polled 2×). TB sweep blocked on reply (scope A/B/C + flagships + fireworks key + admin-env auth OK).
| 7 | be-auth | auth/ | PENDING |
| 8 | be-billing | billing/ + credit/ + entitlements/ + referral/ + tenant/ | PENDING |
| 9 | be-project-intel | project/ + codeintel/ + memory/ | PENDING |
| 10 | be-review-surfaces | prreview/ + codereview/ + finding/ + testgen/ + apitests/ + chat/ + specchat/ + optimiser/ | PENDING |
| 11 | be-governance | contracts/ + standards/ + adr/ + folder/ + activity/ + sharing/ + export/ + compliance/ + governance/ + migration/ + lint/ | PENDING |
| 12 | be-bench-eval | benchmark/ + swebench/ + programbench/ + terminalbench/ + eval/ + test/ + arch/ | PENDING |
| 13 | be-deployment | deployment/ | PENDING |
| 14 | be-api-platform | api/ + validator/ + readymade/ + landing/ + onboarding/ + anonrun/ + contact/ + analytics/ + assessment/ + notification/ + ratelimit/ + security/ + crypto/ + observability/ + config/ + util/ | PENDING |
| 15 | fe-ide | frontend/src/ide/ | PENDING |
| 16 | fe-signal-console | frontend/src/signal/ + console/ + design-system/ + root tsx/css | PENDING |
| 17 | fe-lib-stores | frontend/src/lib/ + stores/ + hooks/ + types/ + build configs | PENDING |
| 18 | infra-prompts | src/main/resources/prompts/ (92 files) | PENDING |
| 19 | infra-build | .github/workflows + scripts/ + observability/ + Dockerfile + pom/vite/ts configs + test-suite shape | PENDING |

## My own deep-dives (not delegated)
- [ ] Model-matrix consistency: trace EVERY consumer of model names (yaml + code). → 09
- [ ] Aggregator redesign (OpenRouter/Fireworks invisible, model-first selection). → 10
- [ ] UX live review: preview_start frontend, login (~/Desktop/local_creds.txt), screenshots desktop+375px. → 11
- [ ] Product/positioning synthesis. → 07
- [ ] Models strategy (cheap-model thesis vs eval data). → 08
- [ ] Terminal-Bench 2.0 sweep, all models in configs. → 12. BLOCKERS: fireworks key absent; budget confirm via Telegram for flagship models.

## Key facts already established (code-verified)
- application.yml names models in 7 places: spring.ai defaults (haiku/gpt-5.4-mini/gemini-3-flash-preview), model-matrix.providers tier maps, model-matrix.models registry (18 entries), legacy-aliases, fallback-chains (ARCHITECT/CAPABLE/UTILITY vocab), free-tier, agent.recovery.capable-models. Plus agent.roles planner/worker/reviewer=anthropic.
- providers tier maps: anthropic ALL tiers=sonnet-4-6; gemini ALL=3.5-flash; openai ALL=gpt-5.4; openrouter tier map points at claude-sonnet-4-6 (!); deepseek ALL=v4-flash-native.
- Two tier vocabularies coexist: PLANNING/WORKHORSE/LIGHTWEIGHT (agent-tier-map) vs ARCHITECT/CAPABLE/UTILITY (chain-tier + fallback-chains).
- failover-order covers only anthropic/gemini/openai. mimo needs bracket-quoted YAML key (dotted-key trap).
- app.tiers.free.monthly-run-limit=5 in yaml.
- pom: Spring Boot 3.5.14, Spring AI 1.1.6, Java 21, spring milestone+snapshot repos still configured, JGit 6.9, Tika 3.3 full parsers, 6 GCP SDKs, Gmail API alongside SMTP, Stripe 32.1, PostHog, Caffeine, JavaParser.
- frontend: vitest EXISTS in devDeps with test scripts; monaco-editor dep present; d3 AND recharts; eslint warn-only (--max-warnings 9999); TS 5.3 / eslint 8 / zustand 4 (all a major behind).
- TB harness configs (~/tbench/es-tb-*.yaml) name 6 models: claude-sonnet-4-6, deepseek-v4-flash, deepseek-v4-pro, gemini-3.5-flash, gpt-5.4, mimo-v2.5-pro.
- Backend :8080 up (old code, PID 43675). Fireworks key NOT in application-local.yml.
