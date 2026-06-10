# 08 — Models strategy: honest assessment of the cheap-models thesis

> You said the model choice is deliberate: "cheaper solutions with acceptable outputs." This doc
> tests that thesis against your own measured data (checkpoints/models-eval-evidence.md — eval
> JSONs, TB runs, the OpenRouter latency study; NOT docs) and gives a strategy. Verdict up front:
> **the thesis is sound, but it's only half-implemented and currently undermeasured.**

## 1. What your own data says

1. **On easy-to-medium work, cheap models tie flagships.** v2-eval LOW band: all providers 98-100
   at $0.04-0.20. MEDIUM: gpt-5.4 at $0.25-0.39 and gemini-flash at $0.40-0.82 match sonnet
   ($0.56-0.59) within judging noise. Tier-eval: gpt-5.4-mini is the best LIGHTWEIGHT call at
   ~$0.0011-0.0017 and 1.5-3.4s.
2. **On the HIGH band, the gap is real.** Anthropic completes $1.28-2.32 at 89-95; gemini/openai
   HIGH runs mostly TIMEOUT. The hard 20-30% of work still belongs to a flagship.
3. **Terminal-Bench proves the cheap anchor.** deepseek-v4-flash-native: 35%→60% resolved on the
   first-20 for ~$0.40 total. That's the headline of your story: a $0.10/M model doing 60% of a
   hard agentic benchmark inside YOUR harness.
4. **Serving route, not model quality, is the current bottleneck for Chinese models.** mimo did
   4/5 on capability but died on throughput (~30 tok/s, p90 48s/call → full-89 abort). Latency
   scales with OUTPUT tokens; agentic loops are output-heavy. A cheap model on a slow route is
   not cheap — it's a TIMEOUT generator (your eval TIMEOUT rows are the same tax).
5. **Your cost numbers for exactly these models are currently fiction.** Every OpenRouter-routed
   model records $0.00 actual cost (BUG-1 in doc 09). Until that's fixed you cannot honestly
   tell a customer (or yourself) what the cheap tier costs.

## 2. Honest gaps in the current posture

- **The tier ladder doesn't actually use cheap models.** All three tiers per provider resolve to
  the SAME flagship-or-near model (yml:86-105: anthropic=sonnet×3, openai=gpt-5.4×3,
  gemini=3.5-flash×3). The LIGHTWEIGHT tier — your cost lever — routes to sonnet on Anthropic.
  The "cheap solutions" thesis isn't expressed in the default matrix AT ALL today; it exists only
  if a customer picks deepseek/openrouter explicitly.
- **Quality consistency across same-caliber models is unmanaged.** Nothing pins "BALANCED-class
  models must clear verifiedScore ≥X on the eval set before being tier-routable." Models enter
  the matrix by hand-edit; there's no promotion gate. (The eval framework exists — tier-eval-v2 +
  v2-eval + TB — but isn't wired as a gate.)
- **The thinking-model lane is blocked**: deepseek-v4-pro-native can't run multi-turn tools
  (reasoning_content echo unsupported in OpenAiNativeToolClient) — you're locked out of the best
  cheap reasoner for HIGH-band work.
- **Prompts are tuned for one model family at a time** (the GPT symptom-anchored rewrite showed
  +4/11 from prompt wording alone). Cheap models need MORE prompt scaffolding, not less; today
  there's one shared worker prompt with provider tweaks behind a default-OFF flag (V219).

## 3. Strategy recommendation

**Position cheap models as the default engine, flagships as the escalation tier — and make the
system, not the customer, own that choice.**

1. **Express the thesis in the matrix** (after doc-09 Phase 0/1): LIGHTWEIGHT → true economy
   models (mini/haiku/deepseek-flash), WORKHORSE → BALANCED class (deepseek-v4-flash-native,
   qwen3-coder-on-fast-route, gemini-flash), PLANNING → flagship (sonnet/gpt-5.4). Complexity
   routing already exists (LOW/MED/HIGH → tier) — it just routes to identical models today.
2. **Escalation, not selection**: when a task fails twice on the economy route, retry once on the
   class above (bounded, logged, cost-capped — the V220 cross-provider fallback skeleton is
   already there, default OFF). This converts "cheap with acceptable output" into "cheap with
   flagship-backed worst case," which is a much stronger sales line.
3. **Fast serving is a prerequisite** — finish Fireworks (slug-verify + latency A/B ≥100 tok/s),
   keep OpenRouter as breadth/fallback with `sort:throughput`, treat any route under ~60 tok/s as
   non-tier-routable regardless of price.
4. **Promotion gate**: a model enters tier rotation only with (a) first-20 TB ≥ threshold,
   (b) v2-eval MED band complete + ≥90, (c) measured tok/s ≥ floor on its chosen route, (d) honest
   $/run from FIXED cost tracking. The TB sweep you've queued is exactly this gate's first dataset.
5. **Unblock thinking models** (reasoning_content echo round-trip in OpenAiNativeToolClient) —
   it's the same pattern you already solved for Gemini thought signatures, and it opens
   deepseek-pro + the GLM/Kimi reasoning lanes for HIGH-band work at ~1/10 flagship price.
6. **Tell the story with the verified score.** "Economy run scored 91, flagship would've cost 9×"
   is your differentiator — surface model display label + cost + score together on the run row
   (today the list shows provider id + cost but no score).

## 4. Same-caliber consistency (your "consistency across models of same caliber" ask)

Define classes by MEASURED bands, not vibes — class membership requires the promotion gate above,
re-validated monthly (models drift). Then: same class ⇒ same prompts (shared base + per-FAMILY
fragment, flag V219 generalized), same caps, same escalation rules, interchangeable in failover.
The doc-09 manifest carries `class:` per model; the doc-10 router only fails over within class.
That triple — gate + shared scaffolding + class-scoped failover — is what makes two models of the
same caliber actually BEHAVE consistently in your pipeline.
