# Models — empirical evidence extracted from result artifacts (NOT docs)

> Sources: scripts/v2-eval-results/*.json, scripts/tier-eval-v2-results/eval-20260520-074505.json,
> /tmp/es-tb-jobs/*, ~/swebench-bench/*.json (h2h). Caveat: v2-eval accuracy is the LLM-judged
> score and runs BEFORE 2026-06 may have silently run the worker on the LIGHTWEIGHT tier
> (known methodology flaw — treat absolute numbers as soft, ratios as directional).

## v2-eval (LOW/MED/HIGH spec bands × anthropic/gemini/openai)
- LOW band: all three providers complete at $0.04–0.20, judged 98–100. Total parity on trivial specs.
- MEDIUM: anthropic $0.56–0.59 (95–100), gemini $0.40–0.82 (100, frequent timeouts), openai $0.25–0.39
  (96–100). OpenAI cheapest, all complete.
- HIGH: anthropic completes $1.28–2.32 (89–95); gemini mostly TIMEOUT (one complete at 69); openai
  mostly TIMEOUT (one complete $3.02 at 74). Anthropic is the only provider that reliably completes
  the HIGH band in these snapshots. Many TIMEOUT rows overall — harness wall-clock fragility is a
  real cost of slow providers, not just UX.
- 2026-06-01 files: nearly all TIMEOUT/incomplete (eval rebuild in progress — excluded).

## tier-eval-v2 (2026-05-20, real agent prompts + tool schemas)
- LIGHTWEIGHT: gpt-5.4-mini $0.0011–0.0017/call, 1.5–3.4s — cheapest + fast. haiku ~3x mini cost.
  gemini-3-flash-preview cost like haiku, latency 3–7s (worst variance).
- PLANNING: gpt-5.4 $0.024–0.028, 13–23s — best cost AND latency. sonnet $0.045–0.071, 29–66s.
  gemini-3-PRO $0.115–0.128, 40–43s (pre-3.5-flash switch; explains why Pro was dropped).

## Terminal-Bench 2.0 (real, blind, production worker)
- deepseek-v4-flash-native first-20: 7/20 (35%) baseline ≈ $0.09 → 12/20 (60%) with terminal tuning
  ≈ $0.40, 48 min. THE cheap-model anchor result.
- mimo-v2.5-pro (OpenRouter): 4/5 on simple-5 slice; full-89 killed at 2/89 for latency (~30 tok/s
  on the Xiaomi backend, p90 48s/call). Capability OK, serving route unusable.
- jobs on disk: es-deepseek-bench10, es-mimo-full89 (partial), es-mimo-simple5.

## SWE-bench Lite (blind harness, small-N)
- Hard-5: anthropic 4/4, gemini 4/4, openai 2/4. Uplift study: ~7% single-shot baseline → 67% with
  the full system. GPT symptom-anchored prompt rewrite: 0/11 → 4/11 on the addressable gap.

## OpenRouter latency study (2026-06-09, 348 calls, mimo)
- Latency scales with OUTPUT tokens (input 90% cached): ~6s base + ~30 tok/s; p50 7s / p90 48s /
  max 107s; 37% of calls >10s. Root cause: OpenRouter price-routing to the slow Xiaomi backend.
  Generation throughput is the dominant latency term for agentic loops ⇒ serving route choice
  (native API vs aggregator vs fast-inference host) matters MORE than model choice for wall-clock.

## Implications for the cheap-models strategy (to develop in 08)
1. Cheap models complete the easy 60-70% of agentic work at 5-30x lower cost — the thesis holds
   THERE (deepseek 60% TB at $0.40; mini/flash parity at LOW/MED v2-eval bands).
2. The HIGH band still belongs to flagships (anthropic completes where others time out).
3. Latency ≠ model: the same weights at 30 tok/s vs 100+ tok/s decide whether long runs finish
   inside wall-clock caps. Fast serving (Fireworks-class) is a prerequisite for the cheap story.
4. Timeouts are the hidden tax of slow/cheap routes (eval TIMEOUT rows, mimo full-89 abort).
