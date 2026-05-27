# Competitive analysis — agentic coding harnesses

> Reverse-engineered design analysis of two competitor coding agents, written to guide our
> harness / code-gen engine and our CLI / VS Code clients. 2026-05-27 (RL-2026-05-051).

## Contents

| Path | What |
|------|------|
| **[COMPARISON.md](COMPARISON.md)** | ⭐ 3-way design+approach comparison: **ExecuteSpec vs Claude Code vs opencode** + the consolidated adoption plan. Start here. |
| [claude-code/](claude-code/) | Anthropic **Claude Code** (from a deobfuscated source dump): README · CODING-TASKS · FEATURES · CODEMAP |
| [opencode/](opencode/) | Open-source **opencode** (anomalyco/opencode): README · CODING-TASKS · FEATURES · CODEMAP |

## The 30-second verdict

- **Both competitors are single streaming tool-loops** ("one strong model + tools"); ExecuteSpec is a
  staged pipeline (classifier→planner→worker→reviewer). On raw SWE-bench resolve-rate the single loops
  beat us *on the same model* — the gap is **harness extraction**, not model.
- **Adopt into the harness:** read-before-write + output-budget (done) · fuzzy edit cascade +
  LSP-diagnostics-in-result · compaction-as-a-loop-task · doom-loop detector · permission ruleset
  (agent = a permission profile) · per-model prompts · cache-boundary discipline.
- **opencode validates the engine-extraction plan:** it IS "core engine as an HTTP server + thin
  clients" (OpenAPI-generated SDK, in-process-or-remote, workspace adapters).
- **Our differentiation:** the product layer (plan gate, multi-repo PRs, governance, scoring, memory)
  + **multi-provider per-task routing** (union 19/30 > 16/13 single-provider) — a lever neither has.

> These describe external code for our internal design reference — patterns + decisions, not code to
> copy. Claude Code is Anthropic proprietary; opencode is open-source (MIT).
