# Project Codemap + RAG — Backlog

> Items deliberately deferred from the v2.5.0 pilot. Ordered by probable pickup.

## 1. Memory overflow → vector spill (P3.4, deferred)

**Trigger:** `ProjectMemoryProvider.MAX_MEMORY_TOKENS` was bumped from 8K → 12K.
When a long-lived project's compacted snapshot crosses 12K, we want to spill
the oldest compacted block to `project_document` (source=`MEMORY_OVERFLOW`)
and truncate the snapshot.

**Why deferred:** no project has crossed even the old 8K cap yet. Snapshot
rewrite + dedup with ledger needs care. Revisit once a real project reports
planner context pressure.

**Design sketch when we pick it up:**
- In `MemoryCompactionService`, after every `doCompact`, check
  snapshot token count. If > 12K, split the snapshot text at heading
  boundaries into ≥2 chunks; the oldest half becomes a doc; the newest half
  replaces the snapshot.
- Doc metadata: `filename = memory-overflow-v<N>.md`,
  `source = MEMORY_OVERFLOW`, `uploaded_by = system`.
- Retrieval already picks these up via the same pgvector path.

## 2. DB blob migration — RESCOPED (not a removal)

**Original plan:** drop DB `content` columns on `project_code_map` +
`project_architecture_doc` once disk is authoritative.

**User decision (2026-04-15):** DB stays authoritative. VMs churn
(Cloud Run / GCE scaling, fresh deploys) and disk is ephemeral.

**Current state (already shipped in v2.5.0 follow-up):**
- Writes go to both DB and disk on every `generate()`.
- `readFile project:` falls back to DB when the disk cache is missing
  (`ReadFileTool.readFromDbFallback`).
- Architecture tab and all controllers already read from DB.

**Remaining optional work:** on boot, detect empty project workspaces
that have DB blobs and eagerly rehydrate disk from DB. Not required —
the `readFile` fallback covers correctness; this would just pre-warm
the disk cache.

## 3. P4.b — OCR / rerank / semantic chunking

- **OCR (scanned PDFs):** Tika already OCRs if Tesseract is on PATH.
  Skip unless a user hits it.
- **Rerank:** second LLM pass to re-score top-K chunks. Adds ~1 embedding
  or planning call per retrieval. Revisit only if retrieval quality
  is observably bad.
- **Semantic chunking:** break on paragraph / heading boundaries rather
  than fixed 500-token windows. Meaningful quality lift for structured
  docs; the current chunker is good enough for free-form prose.

## Shipped in this window

- P3.4 prep: bumped `MAX_MEMORY_TOKENS` 8K → 12K
  ([ProjectMemoryProvider.java:26](../../src/main/java/com/navneet/executespec/memory/ProjectMemoryProvider.java)).
- P3.5 rescope: DB-authoritative + disk-cache fallback
  ([ReadFileTool.java](../../src/main/java/com/navneet/executespec/v2/tool/ReadFileTool.java)).
- Auto-push flag ON by default via V96.
- P4.a: architecture.md diff UI (Diff vs v{N-1} button on the
  Architecture tab, GitHub-style line diff).
- Font parity pass across Project components (all `text-[12]` body rows
  normalised to `text-[11]` to match Run/Pro view density).
