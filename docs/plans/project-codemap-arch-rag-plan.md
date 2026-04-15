# Project Codemap + Unified Architecture + RAG — Plan

> Status: Draft — ready for implementation.
> Author / owner: TBD.
> Depends on: Pilot v3.0 project shell (shipped), ProjectArchitectureService (shipped).

## Goal

Turn a Project into a first-class understanding surface:

1. On project create → user links repos → clicks **Analyse**.
2. System generates per-repo **codemaps**, synthesises a **unified architecture doc**, and writes a short **project summary**. All three land as **physical markdown files** inside a persistent project workspace.
3. Planner + chat + future features read these files on demand via existing tools — they do not carry the blobs in every prompt.
4. Users upload supporting docs (PDFs, specs, API contracts). These are extracted, chunked, embedded into pgvector.
5. Every planner call and chat turn performs **always-on retrieval** (k=3, ≤1500 tokens) from the combined index (project docs + user uploads + overflow memory).

## Invariants

- **RAG is always on.** No flag, no pre-filter. Zero-docs → empty retrieval section, no extra latency.
- **Codemaps and architecture live on disk**, not as DB blobs. DB keeps only metadata (sha, version, path, generatedAt).
- **Idempotent Analyse.** Re-running Analyse refreshes existing codemaps/arch; per-repo regeneration only fires when `lastSyncedSha` drifts.
- **Lazy pre-execution refresh.** When a new execution starts, check staleness on each selected repo; refresh only the drifted ones before planning. No post-run codemap task.
- **Files are versioned.** The project workspace is a local git repo — every regeneration creates a commit. "Compare versions" in the UI becomes `git log` over the file.

## Layout

```
workspace/projects/<projectId>/
├── .git/                       local git — commits each regeneration
├── codemaps/
│   ├── <repo-name>.md          per-repo extracted structure
│   └── ...
├── architecture.md             unified architecture doc
├── summary.md                  2-4 sentence overview (shown on Overview tab)
├── docs/                       user-uploaded reference documents
│   ├── product-spec.pdf.md     Tika-extracted + normalized to markdown
│   └── api-contract.md
└── .meta/
    ├── codemap-head.json       { repoFullName → { sha, generatedAt, path } }
    ├── architecture-head.json  { version, generatedAt, inputShas }
    └── summary-head.json       { generatedAt, sourceSha }
```

Durability: local disk in dev; GCS-backed in prod (reuse `WorkspaceArchive` pattern used for run workspaces).

## DB schema changes

### Shrink existing tables

- `project_code_map`: drop `content` column; keep `(id, project_id, repo_id, path_relative, sha, version, generated_at)`.
- `project_architecture_doc`: drop `content`; keep `(id, project_id, path_relative, version, generated_at, user_annotations)`.
- Lazy migration on first read: if DB still has `content` populated, write to file, null the column, update metadata.

### New tables for RAG

```sql
-- V95 (candidate) — pgvector for RAG over project docs
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE project_document (
    id                VARCHAR(36) PRIMARY KEY,
    project_id        VARCHAR(36) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    path_relative     VARCHAR(500) NOT NULL,    -- relative to project workspace
    source            VARCHAR(32)  NOT NULL,    -- USER_UPLOAD | CODEMAP | ARCHITECTURE | SUMMARY | MEMORY_OVERFLOW
    filename          VARCHAR(255),
    mime_type         VARCHAR(100),
    size_bytes        BIGINT,
    sha256            VARCHAR(64),
    uploaded_by       VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMPTZ,
    UNIQUE (project_id, path_relative)
);

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

## Phasing

### P1 — Project workspace + Analyse + lazy refresh (~2 days)

**Backend**
- `ProjectWorkspaceService` — manages `workspace/projects/<projectId>/`. CRUD + file lock (project-scoped Redis lock for concurrent regenerations). Initialises a local git repo on project create.
- Refactor `ProjectArchitectureService.regenerate()` to write `codemaps/<name>.md`, `architecture.md`, `summary.md` under the project workspace and commit. DB blob writes kept for backward compat this phase.
- Add `POST /api/projects/{id}/analyse` returning `{ status, startedAt }` + SSE event `PROJECT_ANALYSIS_COMPLETE` on finish. `/architecture/generate` becomes an alias.
- Add `ProjectBriefService.buildBrief(projectId)` → returns a compact block to inject into planner + chat prompts:
  ```
  ## PROJECT REFERENCES
  Summary: <2-4 sentences>
  Architecture TOC: <headings from architecture.md>
  Codemaps:
  - repos/hostel-backend/ → codemaps/hostel-backend.md
  - repos/hostel-frontend/ → codemaps/hostel-frontend.md
  User docs:
  - docs/product-spec.pdf.md
  Full content on disk — use `readFile` with `project:` prefix to load any reference.
  ```
- Extend `readFile` tool with a `project:<relative-path>` form resolving to `workspace/projects/<projectId>/<relative-path>` (read-only, sandbox-enforced). Planner + worker get this transparently.
- `V2RunOrchestrator`: before planning a project-scoped run, call `StalenessService.report(projectId)`; for each stale repo, refresh its codemap + re-synthesise architecture + summary. Inject the fresh brief into planner context.

**Frontend**
- Header button `Analyse` on `ProjectDashboardPage` — visible when repos linked, disabled while `PROJECT_ANALYSIS_IN_PROGRESS`, auto-refreshes on SSE.
- Overview tab: top section shows `summary.md` content.
- Architecture tab: renders `architecture.md` content; "Compare with v(N-1)" button (stretch — git diff).

**Exit criteria**
- New project → link 2 repos → Analyse → after ~1-2 min, `summary.md` + `architecture.md` + 2 `codemaps/*.md` exist; Overview + Architecture tabs render their content; DB blob tables have metadata but no content bytes.

### P2 — Document upload + embedding pipeline (~3 days)

**Backend**
- V95 migration (pgvector + `project_document` + `project_document_chunk`).
- `ProjectDocumentService`:
  - `POST /api/projects/{id}/documents` (multipart) — ≤10MB, MIME allowlist (pdf, docx, md, txt, html). Writes to `workspace/projects/<id>/docs/<sanitised-name>`. Persists metadata row.
  - `DELETE /api/projects/{id}/documents/{docId}` — soft-delete, removes chunks.
- `DocumentExtractor` — Apache Tika extracts text → normalised markdown (preserve headings, drop images/tables we can't handle). Write sibling `<name>.md` next to original.
- `EmbeddingJob` (`@Async`) — chunk (500 tokens, 50 overlap), embed via `text-embedding-3-small`, persist. Emits SSE `DOCUMENT_INDEXED`.
- `RetrievalService.retrieve(projectId, query, k=3, tokenCap=1500)` — embed query, cosine top-k, stitch into `## REFERENCE DOCS` block with `source: filename` citations.

**Frontend**
- Documents tab on project — drag-drop upload, progress bar, per-doc status (extracting → embedding → ready), delete. No viewer beyond filename + size (can preview later).

**Exit criteria**
- Upload a 20-page PDF → sees "indexing" → "ready" within ~30s → stored in pgvector → retrieval demo endpoint returns relevant chunks for a query term.

### P3 — Always-on retrieval wired into planner + chat (~2 days)

**Backend**
- `PlannerAgentV2` system prompt assembly: prepend the brief (from P1) + `RetrievalService.retrieve(projectId, userRequest)` output.
- `ProjectChatService` same change — retrieval fires per user message; citations shown in the assistant's response.
- Drop DB `content` columns on code_map + architecture_doc (once all prod projects are migrated). Migration moves any remaining blobs into files.
- Memory overflow hook: when `ProjectMemoryProvider` detects snapshot > 6K tokens, spill the oldest ledger batch into a `MEMORY_OVERFLOW` document — indexed, retrievable. Snapshot stays lean.

**Frontend**
- Chat reply: small "sources" chip row with filenames, click to preview the chunk.
- Planner plan-review: collapsed "References consulted" section listing which docs informed the plan.

**Exit criteria**
- Upload a product spec → ask chat "how does auth work in our system" → answer cites `docs/product-spec.pdf.md` chunk 7. Memory overflow test: force 30+ run project → snapshot stays ≤6K, older entries retrievable.

## Budget math (with P1+P2+P3 live)

| Section | Budget | Source |
|---|---|---|
| Project brief (summary + arch TOC + codemap paths + docs list) | ~800 tokens | injected every call |
| Compacted memory snapshot | ≤ 6000 tokens | injected every call |
| RAG retrieval block (`## REFERENCE DOCS`) | ≤ 1500 tokens | injected every call |
| Planner-specific system prompt | ~3000 tokens | injected every call |
| User message + conversation history | remainder | ~ |
| Hot total | ~11.3K | leaves headroom in a 200K window |

Full codemap/architecture content stays on disk, loaded only when `readFile` pulls it.

## Sharp edges

- **Concurrent regenerations.** Project-scoped Redis lock around `ProjectArchitectureService.regenerate()` — serialises analyses within a project, multiple projects run in parallel.
- **Project workspace cleanup.** Add to `ProjectService.delete()` (and the admin cleanup script): `rm -rf workspace/projects/<id>/`.
- **Sandbox escape.** `readFile` with `project:` prefix must resolve + validate the canonical path stays under `workspace/projects/<id>/`. Same regex guards as run workspace.
- **pgvector install.** Works out of the box on Postgres ≥14 with the extension. On Cloud SQL: enable `vector` extension flag. Document in `infra/`.
- **Embedding cost.** `text-embedding-3-small` at $0.02/1M tokens. Indexing a 100-page PDF (~50K tokens) = $0.001. Per-query cost ~$0.00002. Track in `run_cost_log` under `source=RAG_EMBED` + `RAG_QUERY`.
- **Tika heaviness.** Tika JAR is ~60MB. Either pull in via direct dep and accept the footprint, or spin off an extraction sidecar. Start with direct dep — we already ship a fat JAR.
- **Stale retrieval.** If a doc is deleted, chunks cascade-delete (schema). If it's replaced, we re-extract + re-embed. Version is implicit — old chunks dropped.
- **Citations trust.** LLM might cite a chunk it didn't actually use. Mitigation: retrieve-then-rerank prompt pattern — show retrieved chunks to the LLM with explicit `[S1], [S2], ...` tags, require it to use those tags in its response. We check tag presence in the output.
- **Arch-doc staleness under RAG.** `architecture.md` is on disk but also indexed. When regenerated, the old version's embeddings are stale. Solve: on each regeneration, re-index the file (delete old chunks, embed new).

## Out of scope (explicit)

- Per-run codemap refresh task. Replaced by lazy pre-execution refresh via `StalenessService`.
- Vector DB other than pgvector. Keep infra minimal.
- Cross-project retrieval (asking chat in project A about docs in project B). Hard boundary.
- OCR over scanned PDFs. Text-extractable docs only in P2; OCR is a P4 if needed.
- Real-time collaborative editing of `architecture.md`. Users edit via API or a later editor UI; git history is the audit trail.

## Open questions

- **Rerank step**: do we add a second-pass reranker (e.g. Cohere rerank) after initial cosine retrieval, or accept top-k as-is? Default: skip in P3, revisit if retrieval quality is weak.
- **Chunk strategy**: fixed 500-token vs semantic chunker (sentence boundaries)? Default: fixed with overlap; semantic is P4.
- **Project workspace quota**: do we cap total size per project? Default: 1GB soft cap, reject uploads above; expose via dashboard.

## Rough estimate

| Phase | Days | Cost impact |
|---|---|---|
| P1 | ~2 | baseline — refactors existing services |
| P2 | ~3 | pgvector + Tika dep + embedding pipeline |
| P3 | ~2 | prompt wiring + memory overflow hook |
| **Total** | **~7** | + RAG query cost (negligible per call) |
