# Full-731 SWE-bench Pro — DeepSeek Pro — Dedicated VM Runbook (Monday 2026-07-07)

Goal: one clean **blind pass@1** of the full 731-instance SWE-bench Pro public set on **DeepSeek V4 Pro native**, post-fix harness (`feature/harness-resolution`), official Scale scoring, on a **dedicated x86_64 VM** (not local — the local Mac is 24GB/arm64/Rosetta and disk-bound).

Companion: local sample-20 already scored **12/20 = 60%** on this harness (§results below). This run produces the defensible headline number.

---

## 0. Why a VM (not local)

- **Disk:** 731 images × ~4GB ≈ 2.9 TB — cannot hold at once anywhere. MUST batch (pull→gen→score→prune). A VM with ≥900GB free churns batches without the local 57GB ceiling.
- **Arch:** sweap images are amd64-only. VM x86_64 = native (no Rosetta tax; local was ~2× slower).
- **RAM:** local capped generation concurrency at 2 (24GB). A 62GB VM safely runs the server's `max-concurrent-runs=4`.
- **Duration:** ~2–3 days of batched generation — must survive laptop sleep/close.

## 1. Pre-flight (VM)

1. **Branch:** deploy `feature/harness-resolution` (has B.1 requirements+interface, narrowed Pro diff excludes, exact-path attribution, all Phase 1–3 flags — **keep flags OFF** for the headline run so it's the same config the sample-20 measured; flag A/Bs are a separate later study).
   - Confirm head commit includes `292214c36`-era work (Pro 60% baseline) + `7a3d2cfcf` (glob fix).
2. **Build:** `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -DskipTests package` (VM: system JDK 21). Migration head **V255** — apply to a fresh/clean DB.
3. **Env / config (`swebench.*`):**
   - `suite: swe-bench-pro`
   - `instances-file: /var/swebench/swebench_pro_full.json` (731; rebuild from HF — §2)
   - `output-base: /var/swebench/runs`
   - `max-concurrent-runs: 4` (VM RAM allows; watch heap on batch 1)
   - DeepSeek key present (`spring.ai.deepseek.api-key` / `DEEPSEEK_API_KEY`).
4. **Datasets (rebuild from HF — do NOT trust a stale VM copy):**
   ```
   # datasets-server API, all fields incl. requirements + interface
   # → swebench_pro_full.json (array) + swe_bench_pro_full.jsonl (Scale scorer)
   ```
   (Script: the loop in `k-eval.py`/local rebuild — 731 rows, 8 offsets of 100.)
5. **Scorer:** clone `scaleapi/SWE-bench_Pro-os`, `python -m venv .venv && pip install -r requirements.txt`. Confirm `run_scripts/` has all 731 ids.
6. **Freeze config** (commit SHA, model id, prompts, caps) — the submitted run must be reproducible + describable on the leaderboard form. **250-turn cap compliance:** our DeepSeek PATCH cap is 220 < 250 ✅.

## 2. The batched driver (extend the local tooling)

Local `infra/benchmark/scripts/`:
- `k-eval.py generate --provider deepseek --model deepseek-v4-pro-native --dataset <batch.json> --k 1 --concurrency N --out <dir>` — parallel generate, exact-path manifest, publishes to `/benchmark`.
- `score-watcher.py <manifest> <out>` — pipelined official scoring per instance, posts verifier rewards.

**For 731, wrap in a batch loop** (formalize as `run-full731-batched.py`):
```
BATCH=40                                  # ~160GB images — comfortable on 900GB
for batch in chunks(all_731, BATCH):
    write batch subset json
    docker pull batch images (concurrency 4)      # amd64 native
    k-eval generate --dataset batch.json --concurrency 4 --out runs/full731
    # score-watcher already tails the shared manifest → scores each as it lands
    docker rmi batch images                        # prune before next pull
    df guard: abort if free < 100GB
```
- **One shared manifest + scored.jsonl** across batches (resumable — a crash re-runs only unfinished instances).
- **Concurrency 4** for both pull and generate on the VM (vs local 2).
- Trajectory note: leaderboard submission needs per-instance transcripts. Event log is Redis 24h TTL — **export transcripts to disk during the run** (add to the batch loop: dump `run_step_usage` + event-log per instance right after it completes, before TTL). This was flagged as the missing piece in the resolution log.

## 3. Cost & time (calibrate from local sample-20 actuals)

| Metric | Local sample-20 (Pro) | Full-731 projection |
|---|---|---|
| Per-instance cost | $0.046 avg ($0.92/20) | 731 × $0.046 ≈ **$34** |
| Per-instance wall | ~12.6 min avg (Rosetta) | VM native ~2× faster ≈ 6 min |
| Generation wall | 4.2h/20 serial local | 731 × 6min ÷ 4 concurrent ≈ **18–20h** |
| Scoring | pipelined, ~seconds–min each | overlaps generation; +batch prune overhead |
| Disk churn | — | ~2.9TB pulled+pruned over the run |

**Budget:** ~$34 DeepSeek for the full pass (founder's $20 "to start" cap → confirm bump to ~$40 before launch). Cheap relative to the signal.

## 4. Gates

- **Gate 1 — smoke (VM):** 1 instance, confirm blind spec carries Requirements+Interface (grep `pipeline_run.user_request`), patch capture on `/app`, scorer runs. (~$0.05)
- **Gate 2 — pilot 40 (first batch):** validate the batch loop (pull→gen→score→prune), concurrency-4 stability, JVM heap, disk guard, trajectory export. Produces a real resolved% preview. (~$2)
- **Gate 3 — full 731:** one clean pass, temp 0, concurrency 4, batched by 40. Freeze config first. Mid-run infra-failure reruns ONLY (connect errors, image-pull fails) — never quality failures (auditable pass@1).
- **Gate 4 — official scoring:** all 731 patches scored (pipelined during the run + a final reconcile pass). Output: resolved%, per-language split, 95% CI (~±3.6pp at N=731).
- **Gate 5 — founder review → leaderboard:** Google Form (`scaleapi.github.io/SWE-bench_Pro-os` → Submit); artifacts = predictions JSON (`{instance_id, patch, prefix}`) + per-instance trajectories. Disclose: blind, pass@1, single pass, 220-iter cap, temp 0, cost actuals.

## 5. pass@1 discipline

- ONE prediction set from ONE clean frozen-config pass.
- Infra-failure reruns only (logged with reason in the manifest).
- Internal k>1 (if run) is for OUR error bars only — NEVER mixed into the submitted set.

## 6. Local sample-20 results carried into this run (context)

- **Pro 12/20 = 60%** (js 4/4, ts 1/1, py 4/8, go 3/7), 0 empty patches, $0.92, official Scale scorer, seed-42 sample, blind, k=1.
- **Flash** (same protocol, ~8/20 at last check) — cheaper, lower; Pro is the submission model.
- vs old-prompt VM harness: 6/20 (30%). vs public trackers: DeepSeek V4 Pro ~52% full-set → our harness fixes plausibly put us at/above that band; the 731 run confirms.
- RCA queue (8 Pro fails): 5 go/py logic fails incl. teleport + both flipts, ansible-cd9c thin 2KB patch — next prompt/harness lever likely hides here (post-headline).

## 7. Handoff pointers

- Decision/step log: `infra/benchmark/HARNESS-RESOLUTION-LOG-2026-07-04.md`
- Assessment + resolution path: `docs/plans/executespec-codegen-harness-adversarial-assessment-2026-07-04.md`
- Older full-731 plan (VM specifics, submission mechanics): `infra/benchmark/SWE-BENCH-PRO-PLAN-2026-07-03.md`
- Local artifacts (sample-20): `~/swebench-bench/keval/pro20-postfix{,-flash}/` (manifests + scored.jsonl survive local docker prune).
