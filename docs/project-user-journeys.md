# Project — User Journeys & Workflows

> End-to-end flows for the project-centric workbench.
> Companion to [ARCHITECTURE.md](ARCHITECTURE.md) (the "how") — this doc is the
> "what happens, in what order, for a given user intent".
>
> Last updated: 2026-05-12 (1.0.0-M1). Reflects multi-repo Launch, 3-step
> architecture pipeline, Generate Docs, project memory + chat, RAG, tech-debt
> autofix (Phase 2), external sharing (V175), PR + Code review agents (V172),
> and patch-mode autofix surfaces (V183).
>
> Audience: product, QA, support; also onboarding engineers who want to see the
> pieces fit together without reading the full codebase map.

---

## 0. Glossary

- **Project** — the top-level workspace. Owns the AI provider, a set of linked GitHub repos, a running architecture doc, a ledger/memory, and a docs corpus.
- **Repo** — a `ProjectRepo` row, either `USER`-scoped (private to a creator) or `ORG`-scoped (shared across the org). A repo is registered once and can be linked to multiple projects.
- **Run (V2)** — a single-turn pipeline that takes a natural-language spec + optional repo(s), plans → works → reviews → pushes. Stored in `pipeline_run`.
- **Launch (Execution)** — a higher-level, multi-repo, multi-solution orchestration that plans across repos, kicks off one or more V2 runs, and rolls them up. Stored in `execution_run` + `execution_solution`.
- **Codemap** — a per-repo markdown file (`codemaps/<repo>.md`) summarising the repo's file layout + detected routes + models.
- **Architecture doc** — a project-wide markdown (`architecture.md`) synthesised from all codemaps + user annotations.
- **Summary** — 2-4 sentence plain-prose overview (`summary.md`) derived from the architecture doc, shown on the Project dashboard Overview tab.
- **Memory** — the project's compacted ledger of prior runs. Fed into every planner prompt as `{{PROJECT_MEMORY}}`.
- **Docs** — user-uploaded documents (PDF/DOCX/MD) chunked + embedded into pgvector for retrieval into planner + chat prompts.

---

## 1. Journey: **Start a new project from scratch**

**Who:** a developer who wants a project-shaped workbench for one-or-more new repos.

```
Dashboard → "+ New project" → fill name, description, AI provider → Create
    │
    ▼
ProjectRepoManager tab → "+ Add repo"
    │
    ├─ Pick existing GitHub repo (by full_name, scope=USER or ORG)
    │     └─ Registers ProjectRepo, detects stack, links to project
    │
    └─ Create new GitHub repo (name, description, visibility)
          └─ Calls GitHubRepoService.createRepo → registers ProjectRepo
            → links to project
    │
    ▼
Architecture tab → click "Generate" or "Analyse" on Overview tab
    │
    ▼
Codemaps + architecture.md + summary.md land on disk +
DB rows written. StalenessService cache invalidated.
```

**Cost:** ~$0.005 per repo for codemap + one LIGHTWEIGHT-tier LLM call for the summary.

**What the user sees now:**
- Overview tab: project summary, tokens/cost tiles (both 0), recent runs/PRs panels (empty), score trend (empty).
- Architecture tab: v1 of the arch doc.
- Memory tab: empty.
- Docs tab: empty.

---

## 2. Journey: **Build the first feature on this project**

**Who:** developer who has a project with 1+ linked repos, wants to ship a feature.

**Two flavours** — simple (single-repo V2 Run) and compound (multi-repo Launch).

### 2a. Single-repo feature → **V2 Run**

Use this when the feature lives entirely inside one repo.

```
Project Dashboard → "Launch" button in header
    │
    ▼
"New Execution" modal
  • Requirement: "Add a POST /payments endpoint that ..."
  • Existing repos: pick ONE (checkbox)
  • AI provider: inherited from project default (can override)
  • Confluence / Figma URLs: optional
    │
    ▼
Backend creates ExecutionRun → dispatches a single solution →
RunOrchestrator.submitRun(...)
    │
    ▼ (runs in background)
Plan → Approve (auto if skipApproval=true) → Work → Review → Push PR
    │
    ▼
User sees the new run in Dashboard → Executions list.
Auto-push flag opens a PR on <repo>'s main automatically.
```

Key file locations:
- Orchestrator entry: [RunOrchestrator.submitRun](../src/main/java/com/navneet/executespec/integration/RunOrchestrator.java)
- UI: [ExecutionSetup.tsx](../frontend/src/components/execution/ExecutionSetup.tsx)

### 2b. Multi-repo feature → **Launch**

Use this when a feature spans FE + BE (or multiple services) and you want one orchestrated "mission" instead of juggling multiple runs.

```
Project Dashboard → "Launch" button
    │
    ▼
ExecutionSetup: pick ≥2 existing repos (or add new-repo specs)
    │
    ▼ Setup
ExecutionRun created with status=SETUP → orchestrator.triggerAnalysis()
    │
    ▼ Analysis    (uses the caller's GitHub PAT)
Per-repo codemaps regenerated → unified architecture.md → summary.md
Status → ARCH_REVIEW
    │
    ▼ Arch review (human gate)
User reviews + optionally annotates → Approve
    │
    ▼ Planning
ExecutionPlannerService decomposes into ordered ExecutionSolutions
Each solution is tagged with its target repo (by short-name match)
    │
    ▼ Plan review (human gate)
User Approves
    │
    ▼ Executing
SolutionDependencyResolver builds dependency layers.
Each solution dispatches a V2 run (SolutionExecutionService.submitSolutionRun).
When a solution hits FAILED, the Launch PAUSES and surfaces
SKIP / RETRY / FIX / ATTEMPT_ANYWAY on the Paused card.
    │
    ▼ Complete
Per-repo PRs opened. Cross-linked in the PR bodies.
ExecutionReportPanel shows the full rollup.
```

Key file locations:
- [ExecutionOrchestrator.java](../src/main/java/com/navneet/executespec/execution/service/ExecutionOrchestrator.java)
- [SolutionExecutionService.java](../src/main/java/com/navneet/executespec/execution/service/SolutionExecutionService.java)
- [ExecutionPlannerService.java](../src/main/java/com/navneet/executespec/execution/service/ExecutionPlannerService.java)

**Escape hatches at any step:**
- **Cancel** (header, visible Analysis → Paused) — aborts the Launch cleanly.
- **Abort** (Execute step) — same as Cancel, fails faster.
- **Resume** (Execute step) — re-kicks `executeApprovedPlan` on the existing Launch when the JVM hiccuped or a transient error paused things. Avoids redoing Setup → Analysis → Arch review → Plan.

---

## 3. Journey: **Merge a PR, continue building**

**Who:** developer who shipped one or more PRs from ExecuteSpec, merged them on GitHub, now wants the next round to build on merged code.

```
PR merged on GitHub (outside ExecuteSpec)
    │
    ▼
Project dashboard → PRs tab → click "Refresh"
    │
    ▼ POST /api/projects/{id}/prs/refresh
PrRefreshService polls every non-terminal PR against GitHub.
For any OPEN → MERGED transition:
    │
    ▼ PostMergeCascadeService.onMerged(record)
    ├─ StalenessService.invalidate(projectId)
    ├─ ProjectArchitectureService.generate(projectId) — regenerates
    │    codemaps + architecture.md + summary.md from the new main SHA
    └─ InAppNotificationService.create(...) — "PR merged: <repo>#<n>"
    │
    ▼
Architecture tab now shows v{N+1} of the arch doc; the Diff button
shows exactly what landed.
    │
    ▼
User fires the next Launch. Each solution's V2 run clones the
repo fresh at submit time, so the worker starts from the merged code.
```

**Note:** if webhooks are wired on the GitHub repo (`github.webhook.secret` + `/webhooks/github`), the Refresh button is unnecessary — merge status flips automatically. Refresh is the fallback for brand-new repos created via Launch where no one has configured a webhook yet.

---

## 4. Journey: **I need to inspect or re-review an old run against merged code**

**Who:** developer whose old V2 run produced code that's since been merged; wants to re-run review / tests / preview against the post-merge state.

```
Project dashboard → Tasks tab → pick run
    │
    ▼
InlineRunDetail (only for terminal runs: COMPLETE / PARTIAL / FAILED / CANCELLED)
    ├─ Branch input: leave empty for each repo's default, OR specify
    ├─ Click "Sync"
    │
    ▼ POST /api/runs/{id}/sync?branch=<name>
RunSyncService walks every repo in the workspace, git fetch + git
reset --hard origin/<branch>. Uncommitted local edits are wiped.
    │
    ▼
User can now re-trigger validation / tests / preview against fresh code
without restarting the whole pipeline.
```

---

## 5. Journey: **Upload supporting documents so the planner + chat know about them**

**Who:** developer or PM with a spec PDF / API contract / billing rules doc that the planner should respect.

```
Project dashboard → Docs tab → drag-drop OR pick file
    │
    ▼ POST /api/projects/{id}/documents
ProjectDocumentService.upload(file, user)
    ├─ Writes original to workspace/projects/<id>/docs/<sanitised>
    ├─ Persists project_document row with status=UPLOADED
    └─ Fires async indexAsync:
       UPLOADED → EXTRACTING → Tika parse → sibling .md written
                → EMBEDDING → ChunkingService + EmbeddingService +
                              ChunkStore (pgvector)
                → READY
    │
    ▼
Every subsequent Launch / V2 run:
  • OutlinePlannerAgent receives {{REFERENCE_DOCS}} — top-3 chunks, 1500-token cap.
  • ProjectChatService injects the same block, keyed off the last user turn.
```

**Retrieval is always-on** — no separate toggle. If no docs are READY the block is empty; no cost impact.

---

## 6. Journey: **Ask a question about my project**

**Who:** developer wants architectural or historical context without scrolling through runs.

```
Project dashboard → Chat tab → type question
    │
    ▼ POST /api/projects/{id}/chat
ProjectChatService.buildMessages:
    ├─ System prompt: "You are the ExecuteSpec project assistant…"
    ├─ {{PROJECT_MEMORY}} — compacted ledger of past runs
    ├─ architecture.md content (if present)
    ├─ ## REFERENCE DOCS — retrieval from uploaded docs, keyed off
    │   the latest user turn
    └─ Prior chat history (last 30 messages)
    │
    ▼
LLM answers strictly from context (no fabrication). Users can also
"Launch from chat" to distil the transcript into an execution spec
via compileToSpec, which opens the ExecutionSetup pre-filled.
```

---

## 7. Journey: **Monitor project health**

**Who:** tech lead / PM checking the state of a project.

Dashboard Overview tab = single pane:
- **Metric tiles:** Active / Completed / Failed / Avg score / Tokens / Cost.
- **Project summary** panel: 2-4 sentence overview, Analyse button to regenerate.
- **Recent submissions** panel: last 8 runs/tasks with status + score.
- **Recent pull requests** panel: top PRs with open/merged badge.
- **Score trend** chart: last N runs' overall + per-dimension score (recharts LineChart).

Other tabs:
- **Launches** — full list of cross-repo executions.
- **PRs** — rollup across all runs + launches with Refresh button.
- **Tasks** — every V2 run, with InlineRunDetail.
- **Repos** — the linked catalogue, with staleness badges.
- **Architecture** — current doc + version picker + Diff vs previous.
- **Docs** — uploaded corpus + per-doc status.
- **Memory** — compacted snapshot + recent ledger + token budget bar + Compact button.
- **Chat** — the assistant.
- **Team** — member management (inherits existing org flows).

---

## 8. Workflow: **Which tab / endpoint for which intent?**

| Intent | Where | Endpoint |
|---|---|---|
| Start a one-off coding task in a single repo | Launch → pick 1 existing repo | `POST /api/runs` via ExecutionSetup |
| Start a cross-repo feature | Launch → pick ≥2 repos | `POST /api/projects/{id}/executions` |
| Regenerate architecture after external code changes | Architecture tab → Analyse | `POST /api/projects/{id}/analyse[?branch=]` |
| See what changed in architecture between two runs | Architecture tab → Diff vs v{N-1} | (client-side LCS) |
| Update PR status after merging on GitHub | PRs tab → Refresh | `POST /api/projects/{id}/prs/refresh` |
| Re-run review on merged code | Tasks tab → pick run → Sync | `POST /api/runs/{id}/sync[?branch=]` |
| Un-stick a Launch stuck in Executing | Execute step → Resume | `POST /api/executions/{id}/resume` |
| Abort a Launch at any step | Header → Cancel (or Execute → Abort) | `POST /api/executions/{id}/abort` |
| Ask the project a question | Chat tab | `POST /api/projects/{id}/chat` |
| Distil chat into a Launch | Chat → "Launch from chat" | `POST /api/projects/{id}/chat/compile` → prefill ExecutionSetup |
| Upload a supporting doc for retrieval | Docs tab → Upload | `POST /api/projects/{id}/documents` |
| Compact the project memory | Memory tab → Compact | `POST /api/projects/{id}/memory/compact` |

---

## 9. Error journeys: known pitfalls + how the UI surfaces them

| Situation | Symptom | Recovery |
|---|---|---|
| Private repo + user has no PAT | `/analyse` produces 69-byte "Files: 0" codemaps | Add PAT to user profile; re-Analyse |
| Launch stuck in Executing after JVM hiccup | UI shows tasks QUEUED forever | Click **Resume** on Execute step |
| Launch stuck in Plan | UI shows "Waiting for plan…" for minutes | Cancel + relaunch; check server logs |
| Launch's new-repo creation failed mid-flight | ExecutionSetup returns error; no run created | Retry setup after fixing owner/visibility |
| `abort` fails silently | Check Cancel button disabled state | Refresh; check status — may already be ABORTED |
| Planner produces empty workspace (no code) | `repos/<scratch-name>/` instead of `repos/<repo>/` | Upgrade past v2.5.1 (name-match fallback) |
| Worker runs on wrong provider | Logs show `openai gpt-5.4-mini` despite project=ANTHROPIC | Upgrade past v2.5.1 (provider passthrough fix) |
| PR status stuck at OPEN | No webhook configured on the repo | PRs tab → Refresh (or configure webhook) |
| Doc upload fails with "No EmbeddingModel" | Spring AI 1.1.4 bean not registered | Upgrade past v2.5.0 late-patch (REST-based EmbeddingService) |

---

## 10. What deliberately isn't supported (yet)

- **Editing a running Launch's requirement** — have to Cancel + relaunch.
- **Resuming a FAILED V2 run** — Abort + create a new run; old one is a read-only artefact.
- **Cross-project memory / docs retrieval** — scoped to one project only.
- **Webhook auto-provisioning on new repos** — Launch flow doesn't install a PR webhook; use Refresh in the PRs tab, or configure the webhook manually.
<!-- Worker-side `{{REFERENCE_DOCS}}` injection shipped v2.5.2 via SequentialExecutor.fetchReferenceDocs — bullet retired 2026-04-18. -->
