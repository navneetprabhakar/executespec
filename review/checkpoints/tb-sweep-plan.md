# Terminal-Bench 2.0 sweep — execution plan (checkpoint)

> Status 2026-06-10: prepped, awaiting operator reply on Telegram (scope A/B/C, flagships y/n,
> Fireworks key, admin-env auth OK). Poll /tmp/tg-inbox.log or getUpdates via ~/.executespec/telegram.json.

## Launch contract (verified from ~/tbench/es_harbor_agent.py)
```
cd ~/tbench
ES_PROVIDER=<provider> ES_MODEL=<matrix-model-id> \
ES_PAT=es_pat_… (or ES_ADMIN_EMAIL/ES_ADMIN_PW) \
harbor run --config <es-tb-*.yaml>   # agent import_path: es_harbor_agent:ExecuteSpecAgent
```
- es-tb-base20.yaml = canonical first-20 slice (jobs_dir /tmp/es-tb-jobs, 4 concurrent, 1.5x timeout).
- es-tb-full89.yaml = all 89 tasks. Per-model run = clone config, change job_name + model_name label.
- model_name in yaml is Harbor's LABEL only; actual ES routing = ES_PROVIDER/ES_MODEL env.
- ES_MAX_ITERS default 50 — prior tuned runs used 120-floor server-side; verify server tier config.
- tb-bench-bridge.py → posts TASK_SCORED/RUN_DONE to /api/v2/test/benchmark (in-app dashboard hooks).
  Run it alongside each job: python3 tb-bench-bridge.py --job-dir /tmp/es-tb-jobs/<job_name>.
- Docker 29.3.1 UP. harbor on PATH (~/.local/bin/harbor). Backend must be RESTARTED on the new code
  before fireworks/mimo runs (current :8080 = old code; mimo pricing + fireworks absent).

## Sweep set (model-matrix.models, runnable)
| # | ES_PROVIDER | ES_MODEL | est. $ / 20-task | notes |
|---|------------|----------|------------------|-------|
| 1 | deepseek | deepseek-v4-flash-native | ~$0.40 (measured) | baseline anchor, 12/20 prior best |
| 2 | openrouter | deepseek-v4-pro | ~$1.5-2.5 | OR latency risk |
| 3 | openrouter | qwen3-coder | ~$3-5 | OR latency |
| 4 | openrouter | kimi-k2.6 | ~$4-6 | OR latency |
| 5 | openrouter | mimo-v2.5-pro | ~$1.5-2.5 but SLOW (~30tok/s) | needs new backend (pricing fix) |
| 6 | gemini | gemini-3.5-flash | ~$4-10 | |
| 7 | gemini | gemini-3-flash-preview | ~$3-8 | |
| 8 | anthropic | claude-haiku-4-5-20251001 | ~$3-7 | |
| 9 | openai | gpt-5.4-mini | ~$2.5-6 | |
| 10 | anthropic | claude-sonnet-4-6 | ~$10-25 | flagship — gated on user y |
| 11 | openai | gpt-5.4 | ~$8-20 | flagship — gated on user y |
| +5 | fireworks | qwen3-coder-fw, deepseek-v3-fw, deepseek-v3p1-fw, kimi-k2-fw, glm-4p6-fw | ~$1-4 each | BLOCKED: key + slug verify + backend restart |
EXCLUDED: deepseek-v4-pro-native (thinking-mode; reasoning_content echo unsupported by OpenAiNativeToolClient → multi-turn tool loop breaks), deepseek-v4-flash via OpenRouter (redundant with native), opus/gpt-5.5 enterprise pins (cost, not part of cheap story), gemini-2.5-flash + gemini-3-pro (legacy/not tier-routed).

## Procedure per model
1. Restart backend on new code FIRST (once): JAVA_HOME=/opt/homebrew/opt/openjdk@21 nohup mvn -o spring-boot:run -Dspring-boot.run.profiles=local > /tmp/bench-local.log 2>&1 &
2. Generate config from es-tb-base20.yaml with job_name es-<model>-20-r1, model_name <provider>/<model>.
3. Launch harbor with env; start tb-bench-bridge for dashboard ingest.
4. Watch /tmp/es-tb-jobs/<job>/; collect resolved n/20, cost (RunCostTracker via benchmark dashboard or job result.json), wall-clock.
5. Telegram progress ping per run completion (opt-in rule: long evals).
6. Results table → review/12-terminal-bench-2-results.md.

## Wall-clock strategy
Sequential ≈ 10-16h for 11 models. Run 2 jobs in parallel when providers differ (rate-limit isolation),
keep OpenRouter models serialized (latency + shared gateway). Mac load: 2 jobs × 4 containers OK.
