# Technical Report Outline — "A World Model for Software Engineering: Predicting CI Outcomes Before Code Executes"

**Status: DRAFT skeleton (2026-07-16).** Built from preliminary facts: 200+ open PRs, time-split post-cutoff, ground truth = real CI outcomes, Fable 5 used for label verification only, ~30% margin over Fable 5 / GPT-5.6, data archived in GCS. Fill sections as the scaled eval lands. Target venues: arXiv + company blog + HN Show/Tell; dataset on HuggingFace.

---

## 0. Claims discipline (write this section first, internally)

The report succeeds if a hostile reviewer can reproduce the headline number. Before writing prose, freeze: metric definition, dataset snapshot hash, model versions/dates, prompts for all baselines, and the exclusion log (every PR dropped and why). The single most attackable sentence will be the "30% margin" — define it precisely (relative error reduction vs absolute accuracy points vs AUC delta) and report all three so nobody can accuse metric-shopping.

## 1. Abstract & headline claim

- One sentence: on N post-cutoff open PRs across R repos, [model] predicts CI pass/fail with X% accuracy / Y AUC vs Fable 5's X′/Y′ and GPT-5.6's X″/Y″ — a Z% relative error reduction — at 1/Nth the inference cost.
- Explicit scope statement: CI-outcome prediction, not code generation; what this does and does not imply about SE capability.

## 2. Motivation

- The verification bottleneck (Sonar 2026: 96% distrust / 48% verify; review time +91%).
- Retrospective vs predictive verification: everyone runs CI after; nothing predicts before. Cost and latency implications for agentic pipelines (skip doomed runs, price plans by risk).
- Why "world model": the system models the consequences of a change in an environment (repo + toolchain + CI), not the text of the change.

## 3. Task definition

- Input given to each model (PR diff? issue? repo context? CI config? — specify exactly; identical inputs for all baselines).
- Output: binary pass/fail (or per-check prediction — specify), with confidence.
- Metrics: accuracy, AUC-ROC, F1 on the failure class (failures are the valuable prediction), calibration (ECE) — calibration matters most for the plan-gate product use.

## 4. Dataset (the credibility core)

- Source: 200+ (target: 1,000+) open PRs; selection criteria; language/repo distribution table.
- **Time-split protocol:** all PRs created after the latest training cutoff of every compared model (state each cutoff, with citations).
- **Contamination controls:** beyond time-split — check the fine-tune's own training data for repo overlap; document.
- Ground truth: actual CI outcomes; flaky-CI handling (re-run policy, exclusion rules for infra failures).
- Label verification: dual independent LLM verification — Fable 5 validated extraction/labeling, GPT-5.6 ran an adversarial second pass — with a preserved audit trail (publish it or its hash). State precisely that verifiers touched labels only, never predictions, to preempt the circularity read; add a human spot-check agreement rate on a sample as the third leg.
- Fresh-repo replication: the scaled run uses a disjoint repo set from the pilot (owner has committed to this) — report pilot and replication separately; agreement between them is itself evidence against repo-selection luck.
- Release: eval set + labels + per-model predictions published (GCS → HuggingFace); note license constraints of source repos.

## 5. Models & baselines

- Ours: architecture family, base model, training data description (as disclosable), parameter count, training compute.
- Baselines: Fable 5, GPT-5.6, [add: Sonnet 5, DeepSeek V4, Gemini 3.x for completeness]; identical prompting, temperature, and context; best-effort prompt optimization for baselines (report the prompts) — a weak-baseline accusation is the second most likely attack.
- Cost/latency per prediction for every model.

## 6. Results

- Headline table + CIs (bootstrap over PRs); per-language and per-repo breakdowns; failure-class performance; calibration curves.
- Error analysis: what the world model catches that frontier models miss (with 3–5 concrete PR case studies); what it misses.
- Ablations if available (context components, training data slices).

## 7. Product integration (the ES story, kept short)

- Pass-probability at the plan gate; risk-priced run estimates; continuation-decision routing; cheaper verification loops.
- One live demo pointer: paste a public PR URL → prediction + confidence.

## 8. Limitations & threats to validity

Write this section as if a skeptic authored it: n; repo diversity; CI systems covered (GitHub Actions only?); binary outcome vs root-cause; flakiness noise floor (report measured base flake rate — predictions can't beat the noise floor); generalization to private enterprise repos; the fine-tune's advantage from CI-specific training vs baselines' generality (fair-comparison framing).

## 9. Reproducibility appendix

Dataset hash, model access statement, evaluation harness (open-sourced), prompts verbatim, exclusion log, raw predictions CSV.

---

## Launch packaging (GTM view)

- **Assets:** arXiv report; blog post ("We taught a model to predict CI outcomes — it beats frontier models at 1/50th the cost"); HuggingFace dataset; live demo page; Show HN.
- **Sequencing:** publish only after the 1,000-PR scaled run; the 200-PR result is the internal pilot, referenced in the report as the pilot.
- **The one claim to protect:** never "beats Fable 5 at software engineering" — always "at CI-outcome prediction on this dataset." The narrow claim survives; the broad one dies in comments and takes credibility with it.
