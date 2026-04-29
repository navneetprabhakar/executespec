# Agent Builder — pending plan

> **Status:** PENDING (scope approved 2026-04-29; not yet started).
> **Branch when work begins:** `feature/v3-platform-expansion` (or
> a new branch off it; TBD at execution time).
> **R-### range reserved:** R-327 → R-331 (one per wave below).
> **Author:** 2026-04-29 thread, after competitor analysis +
> 4-angle differentiation discussion.

## One-line pitch

> *"Build a custom agent that ships PRs to your repos, with a hard
> cost cap and repo-level scope. Reusable, shareable across your
> team, governed by your existing tier."*

## What this is

A standalone product surface inside ExecuteSpec where customers
(not us internally) can author named, reusable agents that:

- Take a custom **system prompt** (the agent's brain).
- Use a **subset of the existing V2 worker tool registry** the
  author whitelists at create time.
- Run on a chosen **agent tier** (LIGHTWEIGHT / WORKHORSE /
  PLANNING) — not raw model picker.
- Are **scoped to a set of project repos** the agent is allowed
  to touch (coarse repo-level in v1; fine-grained subdir/glob
  scoping is v2 follow-up).
- Have a **per-run cost cap** (default 5x typical run cost; cap
  hit → run aborts, partial work surfaces as a draft PR).
- Output is a **PR by default**, with `comment` as a secondary
  mode for "post results to existing PR/issue/Slack" agents.
- Are **org-shared by default** (any project member of the org
  can invoke); agent author can flip to USER-private.
- Are **gated to TEAM/ENTERPRISE** for authoring; FREE/PRO can
  use org-shared agents but not author them.

## Strategic positioning (decided)

Four candidate differentiation angles were considered. Decisions:

| Angle | v1? | Rationale |
|---|---|---|
| **PR-first output** (vs chat-first) | **YES — default + `comment` mode** | Reuses V2 pipeline; aligned with our "PRs you can merge" story; only Devin defaults this way and they're $500/mo+. |
| **Repo-scoped permissions** | **PARTIAL — coarse repo-level only** | We have the writer chokepoint (`ExecutespecFolderWriter`) but customers won't grok subdir/glob in v1. Coarse `allowed_repos` first; fine-grained v2. |
| **Cost-budgeted agents** | **YES — per-run cap only** | `V2RunCostTracker` already exists. Solves the #1 enterprise fear (runaway cost). Per-day/per-month caps deferred to v2. |
| **Standards-aware + contract-aware** | **NO — v1.5 / v2** | Real moat (E2/E3 plumbing exists) but most customers haven't populated `org_standard` / `contract_registry` yet. Empty-state risk. Ship Agent Builder v1 first; layer this on once adoption is real. |

## Format decision

**Adopt SKILL.md as the on-disk format**, with ExecuteSpec-specific
fields in the YAML frontmatter (`allowed_repos`, `output_mode`,
`per_run_cost_cap_usd`, `agent_tier`).  Source-of-truth lives at
`.executespec/agents/<name>/SKILL.md` in the customer's repo —
consistent with our existing `.executespec/` folder pattern from
the v2.6.3 enterprise readiness work (E1).

**Why SKILL.md** (industry context as of Dec 2025):

- Anthropic released the **Agent Skills specification as an open
  standard** in December 2025.
- **OpenAI adopted the same format** for Codex CLI and ChatGPT.
- **Cursor adopted SKILL.md** as their Skills format too.

Inventing a proprietary format would isolate us from the cross-
vendor ecosystem.  Adopting SKILL.md (with strict-superset
extensions) means a customer's ExecuteSpec agent definition is
also a valid Claude Skill / Cursor Skill / OpenAI Codex Skill —
they can hand it across tools.

## Wave plan (5 waves; each its own commit + push)

### Wave 1 — Schema + entity (R-327)

- **V157 migration:**
  ```sql
  CREATE TABLE custom_agent (
    id UUID PRIMARY KEY,
    scope VARCHAR(8) NOT NULL CHECK (scope IN ('ORG','USER')),
    owner_org_id  VARCHAR(36),
    owner_user_id VARCHAR(36),
    name          VARCHAR(80)  NOT NULL,
    display_name  VARCHAR(160) NOT NULL,
    description   TEXT,
    system_prompt TEXT NOT NULL,
    agent_tier    VARCHAR(16) NOT NULL,            -- LIGHTWEIGHT/WORKHORSE/PLANNING
    allowed_tools JSONB,                            -- ["readFile","editFile",...]
    allowed_repo_ids JSONB,                         -- ["uuid1","uuid2",...] or NULL
    output_mode   VARCHAR(16) NOT NULL DEFAULT 'PR',-- PR | COMMENT
    per_run_cost_cap_usd NUMERIC(10,4),             -- NULL = use platform default
    requires_approval BOOLEAN NOT NULL DEFAULT TRUE,
    status        VARCHAR(16) NOT NULL DEFAULT 'LISTED',
    created_by    VARCHAR(36) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (scope, owner_org_id, owner_user_id, name)
  );
  CREATE INDEX idx_custom_agent_org    ON custom_agent (owner_org_id, status);
  CREATE INDEX idx_custom_agent_user   ON custom_agent (owner_user_id, status);
  ```
- `CustomAgent` JPA entity + `CustomAgentRepository` + `CustomAgentDto`.
- Backend only — no orchestrator wiring, no UI yet.
- **Tests:** entity round-trip + repository basic queries.

### Wave 2 — WorkerAgentResolver + tool whitelist (R-328)

- New `WorkerAgentResolver` service: given `(orgId, customAgentId)`,
  returns either the system default `WorkerAgentV2` config or a
  `CustomWorkerSpec` containing override fields (system prompt,
  tool whitelist, tier, cost cap, allowed repos).
- `ToolRegistryV2.bind(ctx, allowedToolNames)` overload: filters
  the bound `ToolCallback` list to only the whitelisted tool names
  when non-null. Existing callers unchanged.
- `WorkerAgentV2.run(WorkerTaskRequest)` already accepts
  `systemPrompt`; no change needed.  We pass the resolved override
  through the existing path.
- **Tests:** resolver returns null on missing agent (= default);
  resolver returns spec on existing; tool whitelist correctly
  filters; default behaviour unchanged when whitelist null.

### Wave 3 — Orchestrator wiring + RunRequest field (R-329)

- **V158 migration:**
  ```sql
  ALTER TABLE execution_run  ADD COLUMN custom_agent_id UUID;
  ALTER TABLE pipeline_run    ADD COLUMN custom_agent_id UUID;
  ```
- `RunRequest` + `CreateExecutionRequest` gain `customAgentId`.
- `SequentialExecutorV2` (V2 path) + `SolutionExecutionService`
  (Execution View path) call `WorkerAgentResolver.resolve(...)`
  and pass overrides into `WorkerTaskRequest`.
- Cost-cap enforcement via existing `V2RunCostTracker`: when
  `customAgent.per_run_cost_cap_usd` is set, the tracker emits a
  terminal `BudgetExceeded` event when the running total exceeds
  the cap.  V2 orchestrator translates this to a clean run abort
  with status_reason `AGENT_COST_CAP_HIT`.
- **Tier gate:** `TierEnforcementService.assertCanUseCustomAgent`:
  - TEAM/ENTERPRISE: full access (author + invoke).
  - PRO: invoke org-shared agents only; can't author.
  - FREE: invoke org-shared agents only; can't author.
- **Allowed-repos enforcement:** at `linkProjectRepo`-time check
  in `ExecutionOrchestrator.createExecution` — when a
  `customAgentId` is set, intersect the agent's `allowed_repo_ids`
  with `req.repoIds()`; reject with 403 if the user picked a repo
  the agent isn't allowed to touch.
- **Tests:** orchestrator wiring + tier gate + allowed-repos
  intersection + cost-cap abort end-to-end (mocked LLM).

### Wave 4 — REST API (R-330)

- `agent/` package (new):
  - `CustomAgentController` exposing:
    - `GET    /api/agents` — list visible to caller (org + user).
    - `GET    /api/agents/{id}` — detail.
    - `POST   /api/agents` — create (TEAM/ENTERPRISE).
    - `PUT    /api/agents/{id}` — update (author or ORG_ADMIN).
    - `DELETE /api/agents/{id}` — soft-delete via `status=ARCHIVED`.
  - `CustomAgentService` orchestrates create/update/delete +
    on-create writes `.executespec/agents/<name>/SKILL.md` to
    the customer's repo via `ExecutespecFolderWriter` (so the
    file is committed alongside everything else under
    `.executespec/`).
- **Validation:** name regex `^[a-z][a-z0-9-]{2,79}$`; system_prompt
  ≤ 8000 chars; allowed_tools ⊆ `ToolRegistryV2.allKnownToolNames()`;
  agent_tier ∈ valid tiers; allowed_repo_ids ⊆ project's linked
  repos for the calling org.
- **Tests:** controller integration tests covering happy-path +
  validation rejections + tier-gate 403s.

### Wave 5 — Frontend builder + run-time picker (R-331)

- `/v3/agents` list page — user-owned + org-shared agents,
  "+ New agent" button.
- `/v3/agents/builder` form — name, display name, description,
  system prompt (TextArea, char count), tier picker (3 tiers +
  inline tooltip describing each), tool checklist (10 worker tool
  checkboxes), allowed-repos multiselect (from project's linked
  repos), output mode toggle (PR / COMMENT), per-run cost cap
  input ($ default 5x typical), requires-approval toggle.
- `NewRunModal` + V3 launch surface: "Agent" dropdown — default
  "System default" (= WorkerAgentV2); list of org/user-visible
  custom agents below.  Tier-gated badge ("TEAM+ to author").
- `RepoCard` / project dashboard: surface "agents that can touch
  this repo" as a chip.
- **Tests:** tsc clean; cypress / Playwright smoke for create →
  invoke flow if test infra is ready.

## Out of scope (v1) — explicit list

| Deferred to | Item |
|---|---|
| v1.5 / v2 | Fine-grained subdir / glob path whitelisting |
| v1.5 / v2 | Standards-aware agents (`org_standard` integration) |
| v1.5 / v2 | Contract-aware agents (`BreakingChangeService` integration) |
| v1.5 / v2 | Per-day / per-month / per-org cost caps |
| v2 | Knowledge files / RAG sources per agent |
| v2 | Custom planner agents (only worker is replaceable in v1) |
| v2 | Custom reviewer agents (same coupling) |
| v2 | Schedule trigger (cron) |
| v2 | Event trigger (webhook / PR opened / Slack mention) |
| v2 | Agent versioning / history |
| v2 | Cross-org sharing |
| Roadmap | Public agent marketplace |
| Roadmap | Multi-agent teams (agent-A hands off to agent-B) |

## Effort + cadence

- 5 waves, 5 commits, 5 pushes.
- ~12-15 files touched per wave.
- Tests at every wave; full `mvn test` between waves.
- **M-L total** — roughly the same shape as R-324 (3-checkpoint
  mini-wave) plus 2 UI-and-API waves.
- Estimate 4-5 weeks of focused work if uninterrupted.

## Cross-references

- Format: [Anthropic Agent Skills spec](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview)
- Industry adoption: [Cursor Skills + Custom Agents](https://cursor.com/changelog/2-4),
  Devin Playbooks, OpenAI Codex CLI Skills.
- Dependencies in our codebase: V2 pipeline (`V2RunOrchestrator`),
  `V2RunCostTracker`, `ExecutespecFolderWriter` (E1), `ProjectRepo`
  (R-322 subdir, R-325 size_kb), `TierEnforcementService` (V140
  caps).
- Related plans:
  [pending-agent-builder-plan.md](pending-agent-builder-plan.md) (this file).

## Open questions for execution-time (NOT blockers)

These are micro-decisions that should be made when work starts; not
blockers for greenlight:

- **SKILL.md serialization shape:** how exactly does the
  `allowed_repos: [uuid1, uuid2]` field render in the YAML
  frontmatter — UUIDs verbatim or repo full names?  (Lean toward
  full names so the file is human-readable, with a UUID fallback
  for ambiguous cases.)
- **Cost-cap default value:** "5x typical run cost" needs to be a
  concrete number.  Pull the median from `RunCostLog` for the
  user's tier at agent-create time?  Or fixed at $5/run?
- **Allowed-tools default:** when the user doesn't tick anything
  → all tools allowed, OR a sensible read-only default
  (`readFile`, `listFiles`, `searchFiles`, `gitStatus`,
  `gitDiff`, `completeTask`)?  Probably the latter to encourage
  scoped agents.
- **Approval behaviour for COMMENT-mode agents:** does
  `requires_approval=true` make sense for an agent whose output
  is a Slack message?  (Probably no — auto-execute commenters,
  always-approve PR-mode agents.)
