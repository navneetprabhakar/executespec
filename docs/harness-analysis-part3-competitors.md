# Harness Analysis — Part 3: How Competitors Manage Agentic Pipelines

> Generated: 2026-04-12  
> Context: Post exhaustive eval ($128+, ~40 runs, mean accuracy 56.5%)  
> Purpose: Architectural reference for harness redesign decisions

---

## How Anthropic's Internal Harnesses Work (Claude.ai / Claude Code)

Anthropic's approach with Claude Code: **the tool results are the context window.** There's no conversation history that grows unboundedly — every tool call result is immediately available but the history is managed via explicit compaction policies. More importantly, Claude Code uses **structured tool definitions with strict JSON schemas** — the model is told exactly what parameters exist and what types they must be. There's no XML `<tool_call>` parsing ambiguity. Native tool calling means the finish reason is always `tool_use`, never `end_turn` with embedded XML. This eliminates the entire PATH B parsing layer and the `FABRICATED_TOOL_RESULT_PATTERN` regex — Anthropic models don't fabricate tool results when native tool calling is enforced because the API literally won't accept it.

More critically: Claude Code **has no task decomposition layer**. It runs one agent with one objective and one context window. It doesn't plan 9 tasks for fibonacci — it just writes the file. The "harness" is minimal: a loop that dispatches tool calls and feeds results back. The intelligence is in the model, not the orchestration. When people say Claude Code "just works" for simple tasks, it's because there are zero coordination failure modes — there's only one agent.

For multi-file projects, Claude Code relies on **the model's native codebase awareness** rather than explicit ownership declarations. The model reads whatever files it needs. There's no `ownedFiles` contract that creates artificial walls between tasks. A file can be read and extended by any tool call.

- **Cost of this approach:** No parallelism — everything is sequential.  
- **Benefit:** Zero cross-task wiring bugs because there's only one task.

> **Key insight:** Anthropic deliberately chose sequential over parallel for correctness. ExecuteSpec chose parallel for speed and is paying for it in accuracy.

---

## How OpenAI's Codex / Assistants Manage Context

OpenAI's Responses API and Assistants API use **persistent thread state** — files uploaded once become part of a `vector_store` that all tools can query without re-reading. This is the architectural answer to the 69:1 read:write ratio. When a worker needs to check `shared-contracts.json`, it doesn't call `readFile` — it queries the vector store. The result is retrieved by embedding similarity, not by path.

| Approach | Cost |
|----------|------|
| ExecuteSpec `readFile` call | ~5,000 input tokens |
| OpenAI vector store lookup | ~200 tokens (embedding query) |

**Codex checkpointing:** If the agent fails mid-task, it restores to the last checkpoint rather than restarting from scratch. ExecuteSpec has no checkpointing — a failed task restarts from iteration 0 with a clean context. For a complex task that fails at iteration 20, you discard 20 iterations of context-building and repeat all the `readFile` calls from scratch.

**Explicit artifact tracking:** Every file write is a named artifact with a version. The model doesn't need to `readFile` to know what it wrote — it sees the artifact in its tool results history. This eliminates the read-after-write pattern that drives input token counts up.

**Where OpenAI does worse:**
- Assistants API is slow (polling-based)
- Vector store has retrieval errors for structured code (embedding search on code is less precise than exact path lookup)
- Per-thread state makes parallel execution harder to manage

> ExecuteSpec's path-based `readFile` is actually **more accurate** for code than vector search — the problem is calling it too many times.

---

## How Google's Gemini-Based Harnesses Work

Google's approach in Vertex AI Agent Builder centers on **grounding** and **long context**. Gemini 1.5 Pro: 1M token context. Gemini 2.0 Flash: 2M token context. The Google strategy: *don't manage context, just put everything in it.*

For a 30-file project, concatenate all existing files into the context at the start of each task. No `readFile` calls needed. No dedup cache needed.

| Approach | Token Cost | Predictability |
|----------|-----------|----------------|
| ExecuteSpec iterative reads | Unpredictable (potentially 10x due to repeated reads) | Low |
| Gemini upfront context fill | One large cost per task | High |

**Strict function calling:** Google uses `additionalProperties: false` + all required fields declared in JSON schema. Gemini with strict function calling produces far fewer malformed tool calls than Gemini in conversational mode. ExecuteSpec's `worker-gemini.txt` compensates for the absence of strict mode by embedding the schema in natural language — inherently less reliable.

**Tool result validation:** Google's agentic loops include a "validator" function that checks tool results before feeding them to the next iteration. If `writeFile` returned an error, the validator catches it immediately and forces a retry before the model's next turn. ExecuteSpec dispatches tool results back to the model without structured validation — errors are returned as strings that the model may or may not act on.

---

## How Devin / SWE-agent Manage Multi-File Projects

Devin (Cognition) and SWE-agent (Princeton) both use a **single-agent, terminal-first** approach. There's no planner, no subtask decomposer, no merge orchestrator. The agent has a bash shell and an editor. It reads files with `cat`, writes files with the editor, runs tests with `pytest`/`cargo test`/`npm test`. The test output is ground truth — if tests pass, the task is done.

**The critical architectural difference:** ExecuteSpec has no executable ground truth during task execution. The worker writes files and reports completion. There's no "did this actually work?" check until:
1. CompilerAgent runs (checks compilation only, not behavior)
2. TestGenOrchestrator runs (much later, after COMPLETE status is already set)

Devin runs tests after every significant change. Accuracy is measured in real time, not in a post-hoc validation report.

**SWE-agent's AgentComputer Interface (ACI):**
```
create, open, scroll, search_file, search_dir, find_file, edit
```
No XML parsing. No PATH A vs PATH B. No `FABRICATED_TOOL_RESULT_PATTERN`. The `edit` tool applies a **diff** — it doesn't rewrite the whole file. This eliminates phase-based read-rewrite token waste entirely.

---

## The Unified Insight

Every high-performing agentic harness shares three properties ExecuteSpec currently lacks:

### 1. Ground Truth Feedback During Execution
Tests, not post-hoc review. The agent knows if it's producing correct output while it's still running. ExecuteSpec's validation is entirely post-hoc — by the time accuracy is measured, the run has already cost $5-15.

### 2. Minimal Coordination Surface
Single agent with sequential execution beats multi-agent parallel with ownership contracts for correctness. Parallelism is valuable for speed, but only after the correctness baseline is established. The current parallel architecture creates:
- Cross-task wiring bugs (mod.rs, barrel imports, route registration)
- Constraint propagation failures (NO JPA violated by project_setup)  
- Subtask coordination overhead (12+ init contexts for a fibonacci run)

### 3. Structured Tool Interfaces, Not Prompt-Embedded Schemas
Native function calling or strict JSON schemas eliminate entire categories of parsing bugs and self-play artifacts. The PATH B XML parsing layer is technical debt that every competitor has avoided by using the provider's native tool calling API.

---

## Architectural Questions for Further Discussion

### Q1: Should ExecuteSpec adopt a sequential-first model?
For accuracy, yes. For throughput/speed, no. A hybrid approach: use SINGLE_SHOT (sequential, single-agent) for SINGLE_SHOT and MEDIUM classifications. Only use parallel multi-agent for MULTI_TASK specs where the tasks are genuinely independent. Currently, MEDIUM specs go through the full planner → parallel executor pipeline unnecessarily.

### Q2: Should PATH B (XML prompt-based tool calling) be deprecated?
The maintenance cost of `FABRICATED_TOOL_RESULT_PATTERN`, the XML parser, PATH A vs PATH B routing, and `worker-gemini.txt` / `worker-openai.txt` / `worker.txt` divergence is significant. Anthropic, Google, and OpenAI all support native function calling. A single `worker-native.txt` with strict tool schemas would replace all three files and eliminate the entire self-play defense mechanism.

### Q3: Can inline test execution replace the post-hoc validation report?
The TestPreviewCoordinator already starts a preview and runs tests. The question is whether a lightweight "smoke test" can run after each task merge rather than after COMPLETE. If `POST /health` returns 200 after the framework task is merged, you know routing works before spending budget on 5 more tasks. Early exit on smoke test failure would prevent the "spend $12 producing PARTIAL" outcome.

### Q4: Is the SubtaskDecomposer creating more problems than it solves?
The decomposer was built to prevent iteration exhaustion on complex tasks. But it creates 3-12 execution units per original task, each with cold-start token overhead, each subject to phase-write corruption, and each counted as COMPLETE even if the file is 80% done. The alternative — a single worker with a higher iteration limit and explicit "write one section at a time" instructions in the prompt — may produce better results with lower coordination overhead. Needs A/B testing.

### Q5: What does a "good" harness look like at 90%+ accuracy?
Based on the competitor analysis:
- Triage that forces SINGLE_SHOT for console/algorithm specs (zero coordination overhead)
- Sequential execution for MEDIUM specs (1 worker, full codebase visibility, no ownership walls)
- Parallel execution only for verified-independent MULTI_TASK specs with explicit interface contracts
- Smoke test after each task merge (early failure detection)
- Native function calling for all providers (no XML parsing layer)
- Per-task constraint injection (HARD_CONSTRAINTS block in every worker prompt)
- Reviewer with wiring-specific phase (mod.rs, barrel imports, route registration)

---

*See also: Part 1 (Accuracy Root Causes), Part 2 (Cost Explosion Analysis), Part 4 (Prioritized Fix List)*
