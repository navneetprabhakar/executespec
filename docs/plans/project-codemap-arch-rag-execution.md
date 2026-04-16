# Project Codemap + RAG — Execution Plan

> Companion to [project-codemap-arch-rag-plan.md](project-codemap-arch-rag-plan.md).
> This is the concrete day-by-day implementation order: files to touch, schema
> deltas, endpoints, tests to write, verification steps. Read the feature doc
> first for the "why" and the layout; this one is just the "how".

## Pre-flight

- [ ] Confirm Postgres is ≥ 14 (for `pgvector`). Local: Postgres.app 18 already ≥ 14 ✓.
- [ ] Add vector extension to local DB: `CREATE EXTENSION IF NOT EXISTS vector;`. Gate behind V95 migration in P2, but local dev may need it earlier.
- [ ] Ensure `workspace/` parent dir is writeable by the Spring Boot process (it already is — run workspaces live there).
- [ ] No breaking migrations in P1 — DB content columns stay, just stop being written.

---

## Phase 1 — Project Workspace + Analyse + Lazy Refresh

### P1.1 — `ProjectWorkspaceService`

**Create:** `src/main/java/com/navneet/executespec/project/workspace/ProjectWorkspaceService.java`

Responsibilities:
- `Path rootFor(String projectId)` → `workspace/projects/<projectId>/`.
- `Path resolve(String projectId, String relativePath)` with traversal guards (canonical path must start with root).
- `initIfMissing(String projectId)` — creates dirs + `git init` + initial commit.
- `writeAndCommit(projectId, relativePath, content, commitMsg)` — atomic rename + git add + git commit. Project-scoped lock (Redis: `project:ws-lock:<id>`, 5-min TTL).
- `readFile(projectId, relativePath)` with the same path guard (returns `Optional<String>`).
- `delete(projectId)` — `rm -rf`.

Test: unit tests for path traversal (reject `../`, absolute paths), concurrency (two threads writing the same file serialise).

### P1.2 — Refactor `ProjectArchitectureService`

**Touch:** `src/main/java/com/navneet/executespec/codeintel/ProjectArchitectureService.java`

Changes:
- Inject `ProjectWorkspaceService`.
- On `regenerate(projectId)`:
  1. For each linked repo: generate codemap markdown (existing logic) → `workspaceService.writeAndCommit(projectId, "codemaps/" + repoShortName + ".md", content, "codemap: <repo> @ <sha>")`.
  2. Call the unified arch LLM step → `architecture.md`.
  3. Call the new `ProjectBriefService.summarise(archDoc, codemaps)` → `summary.md` (2-4 sentences).
- Metadata writes: update `.meta/codemap-head.json` + `architecture-head.json` + `summary-head.json`.
- DB row writes continue (metadata only — content columns stay populated for a transition window; set to the file path + null the blob once feature-flag `project.codemap.files-only` flips on).

Test: integration test creates a project with a tiny repo + fixture codemap → verifies file on disk + git commit created.

### P1.3 — New endpoint `/analyse`

**Create:** `src/main/java/com/navneet/executespec/codeintel/ProjectAnalyseController.java`
- `POST /api/projects/{id}/analyse` → 202 + `{ status, startedAt }`. Dispatches `ProjectArchitectureService.regenerate()` on a virtual thread. Emits SSE `PROJECT_ANALYSIS_STARTED` + `PROJECT_ANALYSIS_COMPLETE` on channel `sse:channel:project:<id>`.
- Preserve `POST /api/projects/{id}/architecture/generate` as a deprecated alias (logs deprecation warning).

Test: smoke via curl against a 2-repo project; inspect SSE with the dev token.

### P1.4 — `ProjectBriefService`

**Create:** `src/main/java/com/navneet/executespec/codeintel/ProjectBriefService.java`

- `String buildBrief(String projectId)` — reads `summary.md`, extracts headings from `architecture.md` (regex `^#{1,3} `), lists codemaps + user docs from `.meta/*.json`. Returns ~800-token block.
- `summarise(archDoc, codemaps)` — LIGHTWEIGHT-tier LLM call (reuse `RunTitleSummarizer` pattern — no cost tracker plumbing) → 2-4 sentence overview. Written to `summary.md`.

Test: given a known arch doc, summary is ≤ 4 sentences, mentions the repos in scope.

### P1.5 — Extend `readFile` with `project:` prefix

**Touch:** `src/main/java/com/navneet/executespec/v2/tool/ReadFileTool.java`

- If argument path starts with `project:`, resolve via `ProjectWorkspaceService.readFile(context.runId → run.projectId, rest)`.
- Enforce: only fires when the run has a `projectId`. Otherwise return "project workspace not available for this run".
- Same 8K char truncation as today.

Test: tool invocation in a project-scoped run returns `architecture.md`; in a no-project run returns the guard message.

### P1.6 — `V2RunOrchestrator` pre-execution refresh

**Touch:** `src/main/java/com/navneet/executespec/v2/integration/V2RunOrchestrator.java`

Before calling the planner for project-scoped runs:
- Call `StalenessService.report(projectId)` (already cached 5 min).
- For each stale repo in `selectedRepoIds`: `ProjectArchitectureService.regenerateForRepo(projectId, repoId)` (new method — partial regen, only touches that repo's codemap + re-synthesises arch + summary if any codemap changed).
- Inject `projectBriefService.buildBrief(projectId)` into the planner's system prompt (add new placeholder `{{PROJECT_BRIEF}}` in `prompts/v2/planner.md`).

Test: submit a run against a project whose repo SHA has drifted → log confirms codemap regen fired for that repo only.

### P1.7 — Frontend: Analyse button + Overview summary

**Touch:** `frontend/src/pages/project/ProjectDashboardPage.tsx` + `frontend/src/components/project/ArchitectureViewer.tsx`

- Header: `Analyse` button (ExecuteSpec accent-blue ghost, Loader2 when in-progress).
- Call `POST /api/projects/{id}/analyse`; subscribe to SSE channel to flip state back.
- Overview tab: new top panel showing `summary.md` content (fetch via new `GET /api/projects/{id}/summary`).
- Architecture tab: already renders arch — now fetches `architecture.md` via the same endpoint pattern (`GET /api/projects/{id}/architecture` returns file content from disk, not DB).

### P1.8 — Verify

- [ ] `mvn compile -q` + `npm --prefix frontend run build` green.
- [ ] Create project → link 2 repos → Analyse → in ~90s: `workspace/projects/<id>/` has summary.md, architecture.md, codemaps/*.md, `.git/` shows one commit.
- [ ] Submit a project run → log shows `PROJECT_BRIEF` injected in planner prompt (grep `prompt_audit`).
- [ ] Let repo drift on GH → submit a new run → log shows partial regen.

Exit: **P1 done.**

---

## Phase 2 — Document Upload + Embedding

### P2.1 — V95 migration

**Create:** `src/main/resources/db/migration/V95__pgvector_project_documents.sql`

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE project_document (
    id                VARCHAR(36) PRIMARY KEY,
    project_id        VARCHAR(36) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    path_relative     VARCHAR(500) NOT NULL,
    source            VARCHAR(32)  NOT NULL,
    filename          VARCHAR(255),
    mime_type         VARCHAR(100),
    size_bytes        BIGINT,
    sha256            VARCHAR(64),
    uploaded_by       VARCHAR(64),
    status            VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',  -- UPLOADED | EXTRACTING | EMBEDDING | READY | FAILED
    failure_reason    TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMPTZ,
    UNIQUE (project_id, path_relative)
);

CREATE INDEX idx_pd_project ON project_document (project_id, status);

CREATE TABLE project_document_chunk (
    id            VARCHAR(36) PRIMARY KEY,
    document_id   VARCHAR(36) NOT NULL REFERENCES project_document(id) ON DELETE CASCADE,
    chunk_index   INT         NOT NULL,
    content       TEXT        NOT NULL,
    token_count   INT         NOT NULL,
    embedding     vector(1536) NOT NULL
);

CREATE INDEX idx_pdc_doc ON project_document_chunk (document_id, chunk_index);
CREATE INDEX idx_pdc_embedding ON project_document_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

Run `flyway migrate` against local dev DB to confirm.

### P2.2 — Tika dependency

**Touch:** `pom.xml` — add `org.apache.tika:tika-parsers-standard-package` (~60MB, accept the fat-JAR impact).

### P2.3 — Entities + repositories

**Create:**
- `src/main/java/com/navneet/executespec/project/rag/entity/ProjectDocument.java`
- `src/main/java/com/navneet/executespec/project/rag/entity/ProjectDocumentChunk.java` (Hibernate doesn't natively support pgvector — use `@JdbcTypeCode(SqlTypes.ARRAY)` with `float[]`, or a custom `UserType`. We'll use native SQL for inserts + nearest-neighbour query via JPQL-escape for now.)
- Repositories for both.

### P2.4 — `DocumentExtractor`

**Create:** `src/main/java/com/navneet/executespec/project/rag/DocumentExtractor.java`

- `String extract(Path file, String mimeType)` — Tika `AutoDetectParser` with `BodyContentHandler(-1)` (no limit). Return markdown-ish normalised text (preserve line breaks, strip NBSP, normalise multiple whitespace).
- Separate `.md` sibling written: `<original-filename>.md`.

### P2.5 — `EmbeddingService`

**Create:** `src/main/java/com/navneet/executespec/project/rag/EmbeddingService.java`

- Spring AI `OpenAiEmbeddingModel` with `text-embedding-3-small` (already in the dependency graph).
- `List<float[]> embed(List<String> texts)` — batch up to 100 per call.
- Retry once on transient 5xx.
- Track cost via `V2RunCostTracker`? No — different surface. Write to `run_cost_log` with `source=RAG_EMBED` + null runId (aggregate per project).

### P2.6 — `ChunkingService`

**Create:** `src/main/java/com/navneet/executespec/project/rag/ChunkingService.java`

- Fixed 500-token chunks, 50-token overlap. Token count via the same heuristic we use elsewhere (chars / 4).
- Don't split mid-code-block — if a chunk boundary lands inside ``` fence, extend to the closing fence (up to +200 tokens).

### P2.7 — `ProjectDocumentService`

**Create:** `src/main/java/com/navneet/executespec/project/rag/ProjectDocumentService.java`

- `ProjectDocument upload(projectId, MultipartFile, user)`:
  1. Validate size ≤ 10MB, MIME in allowlist.
  2. Write original to `docs/<sanitised>` via `ProjectWorkspaceService`.
  3. Persist `project_document` row with status `UPLOADED`.
  4. Fire `@Async` indexing: status `EXTRACTING` → write `<name>.md` → status `EMBEDDING` → chunk + embed + insert rows → status `READY`.
  5. On failure: status `FAILED`, `failure_reason`, SSE error event.
- `void delete(projectId, documentId)`: soft-delete + chunk cascade.
- `Page<ProjectDocument> list(projectId)`.

### P2.8 — `RetrievalService`

**Create:** `src/main/java/com/navneet/executespec/project/rag/RetrievalService.java`

- `String retrieve(projectId, query, k=3, tokenCap=1500)`:
  1. Embed the query.
  2. SQL: `SELECT dc.content, dc.token_count, d.filename, dc.chunk_index FROM project_document_chunk dc JOIN project_document d ON d.id = dc.document_id WHERE d.project_id = ? AND d.status = 'READY' AND d.deleted_at IS NULL ORDER BY dc.embedding <=> ?::vector LIMIT ?;`
  3. Assemble output:
     ```
     ## REFERENCE DOCS
     [S1] docs/product-spec.pdf.md (chunk 7)
     <content>
     
     [S2] docs/api-contract.md (chunk 2)
     <content>
     ```
  4. Trim to `tokenCap`; if mid-chunk truncation needed, drop the last partial.
  5. If `readyCount == 0`, return `""`.
- Counter metric `executespec.rag.retrieve.latency` + `executespec.rag.retrieve.hits`.

### P2.9 — Controller + Frontend

**Create:**
- `src/main/java/com/navneet/executespec/project/rag/ProjectDocumentController.java`:
  - `POST /api/projects/{id}/documents` multipart, returns 202 + doc id.
  - `GET /api/projects/{id}/documents` list.
  - `DELETE /api/projects/{id}/documents/{docId}`.
- `frontend/src/components/project/ProjectDocumentsPanel.tsx` — Documents tab. Drag-drop, per-doc status (with SSE), delete.

### P2.10 — Verify

- [ ] Flyway migrates clean locally.
- [ ] Upload a 20-page PDF via UI → within 30s sees READY + ~40 chunks in `project_document_chunk`.
- [ ] Admin endpoint `GET /api/projects/{id}/documents/_test-retrieve?q=billing` returns plausible chunks.

Exit: **P2 done.**

---

## Phase 3 — Always-On Retrieval Wiring

### P3.1 — Planner prompt integration

**Touch:** `src/main/java/com/navneet/executespec/v2/agent/PlannerAgentV2.java` + `src/main/resources/prompts/v2/planner.md`

- Add `{{REFERENCE_DOCS}}` placeholder to `planner.md` just below the existing `{{PROJECT_MEMORY}}` block.
- `PlannerAgentV2.plan()` calls `retrievalService.retrieve(projectId, req.spec())` and substitutes. Non-project runs get empty.
- Single-shot path: also fires retrieval, even though SINGLE_SHOT bypasses the full LLM call today. Passes the retrieved block into the worker's first message.

### P3.2 — Chat integration

**Touch:** `src/main/java/com/navneet/executespec/memory/ProjectChatService.java`

- In `buildMessages`, after the system prompt's memory + arch sections: call `retrievalService.retrieve(projectId, userText)` with `k=3, cap=1500`. Append as a `## REFERENCE DOCS` block.
- Response from the LLM may include `[S1]` / `[S2]` tags — frontend extracts these and renders a small "sources" chip row.

### P3.3 — Chat UI citations

**Touch:** `frontend/src/components/project/ProjectChatPanel.tsx`

- Parse `[S1]`-style tags in assistant messages. Below the message bubble, render clickable chips: "S1 · product-spec.pdf.md". Click opens a drawer with the chunk content.
- New endpoint: `GET /api/projects/{id}/documents/{docId}/chunks/{idx}` returns the chunk text.

### P3.4 — Memory overflow hook

**Touch:** `src/main/java/com/navneet/executespec/memory/ProjectMemoryProvider.java` + `MemoryCompactionService.java`

- After compaction, if snapshot > 6K tokens, take the oldest compacted block and spill it into a `project_document` with `source=MEMORY_OVERFLOW`, filename `memory-overflow-v<N>.md`. Index normally. Snapshot truncated to cover only the most recent compacted runs.
- Retrieval picks it up automatically (no source filter needed).

### P3.5 — Migration off DB blobs

**Touch:** `src/main/resources/db/migration/V96__project_blob_move.sql` + `ProjectArchitectureService`.

Either:
- A one-shot Flyway Java migration that reads each row with `content IS NOT NULL`, writes to disk, nulls the column.
- Or a lazy migration: on first read, if content is on disk use it; else load blob, write to disk, null the column in DB, return.

Lazy is safer — go with that. V96 is schema-only: drop `NOT NULL` constraints on `content` columns; keep columns (null) until a later cleanup migration.

### P3.6 — Verify

- [ ] Upload `billing-rules.pdf` → ask chat "how do late fees work?" → answer cites `[S1] billing-rules.pdf.md`.
- [ ] Submit a feature request whose answer is only in the uploaded doc → planner references it in plan summary.
- [ ] Stress memory: inject 40 synthetic ledger entries → confirm snapshot stays ≤ 6K, older entries retrievable by chat.
- [ ] `prompt_audit` shows `## REFERENCE DOCS` block present on planner + chat system prompts.

Exit: **P3 done.**

---

## Test matrix (run after each phase)

| Scenario | P1 | P2 | P3 |
|---|---|---|---|
| Analyse fresh project | ✓ | ✓ | ✓ |
| Re-analyse existing | ✓ | ✓ | ✓ |
| Pre-execution staleness refresh | ✓ | ✓ | ✓ |
| Upload + index PDF | | ✓ | ✓ |
| Delete document | | ✓ | ✓ |
| Chat retrieval with citations | | | ✓ |
| Planner prompt has REFERENCE_DOCS | | | ✓ |
| Memory overflow → indexed | | | ✓ |
| Concurrent regeneration locked | ✓ | ✓ | ✓ |
| Sandbox escape via `project:../` | ✓ | ✓ | ✓ |

## Rollback plan

- P1: feature flag `project.codemap.files-only` (default OFF) — when OFF, `ProjectArchitectureService.regenerate()` writes files AND DB blob. Can revert to blob reads by removing `project:` path resolution from `readFile`.
- P2: upload endpoint can be feature-flagged (`project.rag.uploads-enabled`). pgvector extension stays installed — no rollback cost.
- P3: `{{REFERENCE_DOCS}}` substitution + chat retrieval both gated on `project.rag.retrieval-enabled`. Toggle OFF to disable RAG immediately — prompts go back to P1 shape.

## Commit discipline

- One PR per phase (P1 → P2 → P3). Each PR has its own `mvn compile -q` + `npm run build` pass, SQL migrations applied locally, verification matrix ticked.
- Migration numbering: reserve V95 (pgvector) + V96 (blob constraint relaxation) before starting P2. If unrelated migrations land in between, bump.
- Feature branches: `feature/project-workspace` (P1), `feature/project-rag` (P2), `feature/rag-wiring` (P3). Merge in order.

## Out of scope / parking lot

- UI editor for `architecture.md` — P4 candidate.
- Rerank step post-retrieval — revisit if quality < expected.
- Semantic chunking — P4.
- OCR over scanned PDFs.
- Cross-project retrieval.
- Quota enforcement per project (soft cap TODO after P2).
