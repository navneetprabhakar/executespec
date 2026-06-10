# be-project-intel — raw findings

Reviewed as-is on branch `benchmark` (uncommitted changes present but none touch these packages). Every `.java` file in scope read in full; all dead-code/inconsistency claims grep-verified against `src/main`, `src/test`, and `frontend/src`. Docs were NOT consulted; `application.yml` + Flyway SQL + prompt files used only to verify code references.

## 1. Inventory

| Package | Files | LOC | Shape |
|---|---|---|---|
| `project/` | 44 | 5,912 | workspace (2), security (1), service (4), controller (1), dashboard (1), dto (5), entity (2), repository (2), estimation (1), scores (1), techdebt (2), template (4), rag (11), pr (8) |
| `codeintel/` | 28 | 4,377 | generators (CodeMapGenerator, RepoArchitectureGenerator, ArchitectureDocGenerator, ProjectDocGeneratorService, ProjectBriefService, FilePurposeSummaryService, RunCodeMapService), ProjectArchitectureService (783 — largest), StalenessService, TieredCodemapSelector, VersionRetentionService, ArchitectureDiffService, ArchLlmDispatch, JavaAstSymbolExtractor, 2 controllers, 6 entities, 6 repositories |
| `memory/` | 13 | 2,642 | ProjectChatService (619), chattools/ProjectChatTools (1,149 — largest file in scope), RunLedgerAgent, MemoryCompactionService, ProjectMemoryProvider, 2 controllers, 3 entities, 3 repositories |
| Tests | 11 | 1,531 | 8 codeintel/project unit tests, 0 memory tests |

## 2. Findings

### Real bugs first

**[BUG] Uploaded binary documents are corrupted on write — the Tika RAG pipeline cannot work for PDF/DOCX/non-ASCII text.**
`project/rag/ProjectDocumentService.java:45` reads upload bytes as a Latin-1 string (`new String(file.getBytes(), ISO_8859_1)`) and hands it to `ProjectWorkspaceService.writeAndCommit`, which writes with `Files.writeString` — **UTF-8** (`project/workspace/ProjectWorkspaceService.java:117`). Every byte ≥ 0x80 is re-encoded as a 2-byte UTF-8 sequence: PDFs get their xref offsets and Flate streams destroyed, DOCX (a ZIP) becomes unreadable, and even UTF-8 text files get double-encoded mojibake. Tika then extracts from the corrupted on-disk file (`ProjectDocumentService.java:78-79`). Only pure-ASCII uploads survive intact. The stored `sha256` (line 59) is computed from the ORIGINAL bytes, so it doesn't even match what's on disk. Fix: pass bytes through (`Files.write(tmp, bytes)`) or add a byte-oriented `writeAndCommit` overload.

**[BUG] `indexAsync` is a self-invocation — document indexing is synchronous despite the `@Async` annotation and the 202 response.**
`ProjectDocumentService.upload` (line 66) calls `this.indexAsync(doc.getId())` on the same bean; Spring's `@Async` proxy (standard proxy mode — `@EnableAsync` at `ExecutespecApplication.java:39`, no AspectJ) is bypassed, so extract→embed→insert runs inline on the request thread before the controller's `202 ACCEPTED` is returned (`ProjectDocumentController.java:36`). The UPLOADED→EXTRACTING→EMBEDDING→READY status machine the entity defines never has an observable intermediate state, and a slow Tika/OpenAI call pins a Tomcat thread. Same self-invocation pattern, lower stakes: `FilePurposeSummaryService.summariseFile` → `this.generateAndPersist` (`codeintel/FilePurposeSummaryService.java:125,132`) — the `@Transactional` there is dead; and `StackRefreshJob.refreshAll` → `this.runOnce()` (`StackRefreshJob.java:46-48`) — the `@Transactional` on `runOnce` does NOT apply on the scheduled path (accidentally good, see below) but DOES apply when admin tooling calls `runOnce()` externally, wrapping a whole-fleet GitHub loop with `Thread.sleep(200)` per repo in one transaction.

**[BUG] `project_generated_doc.source_shas_fingerprint` is `VARCHAR(255)` but receives the full per-repo SHA JSON — doc generation breaks at ~4 linked repos.**
`ProjectDocGeneratorService.generate` persists `arch.getGeneratedFromShas()` verbatim as the cache key (`codeintel/ProjectDocGeneratorService.java:194,273`). That JSON is `{"owner/repo":"<40-char sha>", ...}` ≈ 60–80 chars per repo; V195 created the column as `VARCHAR(255)` (`db/migration/V195__generated_doc_cache_key.sql`). A project with 4+ linked repos (3 with long names) exceeds 255 → the INSERT throws, the `@Transactional` rolls back, and `POST /api/projects/{id}/docs/{type}/generate` 500s after burning the full 15-40s LLM call. Also note line 194's comment "already a canonical JSON string" is wrong — the stored column is the unsorted LinkedHashMap serialization, not `serialiseFingerprint` output (functionally OK today only because both compare sides derive from the same row).

**[BUG cluster] `@Transactional` wrapping LLM and GitHub network calls — the exact bug class this codebase documents as fixed for `syncRepo` and `ProjectArchitectureService.generate`, still live in 8+ places.**
`ProjectRepoService.syncRepo`'s javadoc (lines 188-205) and `ProjectArchitectureService.generate`'s (lines 124-128) both explain that holding a pooled Hikari connection across multi-second network/LLM work exhausted the pool and hung the app. The same pattern survives in:
- `memory/ProjectChatService.java:316` — `send()` is `@Transactional` and contains the whole manual tool loop: up to 6 LLM rounds (`MAX_TOOL_ROUNDS`, line 301) plus tool execution; the bound tools include `regenerate_architecture` (full 3-step arch pipeline, 30-90s+ per its own description) and `run_code_review`. A single chat turn can hold a DB connection + open transaction for **minutes**. `compactHistory` (line 97) likewise wraps its LLM call in the tx.
- `codeintel/ProjectDocGeneratorService.java:173` — `generate()` is `@Transactional` around a self-described "15-40s" `fallbackLlmRouter.callWithFallback` (line 227).
- `codeintel/RunCodeMapService.java:63,76` — `generateForRun` is `@Transactional` around `codeMapGenerator.generateWithSha` (up to ~54 GitHub reads) plus a direct `anthropicModel.call` LLM summarise. Called once per eligible BUILD run from `RunOrchestrator` (~line 1049): every paid-tier existing-repo run pins a connection for the whole scan.
- `memory/RunLedgerAgent.java:50` — `generateLedgerEntry` is `@Transactional` around one LIGHTWEIGHT LLM call AND, every 10th run, the nested `MemoryCompactionService.compactIfNeeded` joins the same tx for a second LLM call. Called at run completion from `RunOrchestrator` (~line 1665). `MemoryCompactionService.compactNow` (line 49) has the same shape via the Memory-tab button.
- `project/service/ProjectRepoService.java:65` — `createRepo` is `@Transactional` with up to 4 GitHub calls inside (`getRepoSizeKb` line 111, `stackDetector.detect` line 150, `getBranchHeadSha` line 151, `detectMonorepoSubdir` line 140). `createAndLinkNewRepo` (line 414: `gitHub.createRepo` + `installWebhook`) and `installWebhook` (line 497) repeat it.
- `project/pr/CiFailureAutoFixService.java:98` — `onCheckRunFailed` is `@Transactional` through `findingSubmitter.submit` (run dispatch).
Severity: with the default Hikari pool, a handful of concurrent chat-turns-with-tools or doc-gens stalls all DB access. These are the highest-leverage fixes in the review.

**[BUG] `FilePurposeSummaryService` is missing from `agent-tier-map` — purpose summaries resolve to WORKHORSE (flagship model), not the LIGHTWEIGHT/Haiku tier the design assumes.**
`codeintel/FilePurposeSummaryService.java:145` resolves via `modelResolver.resolve("FilePurposeSummaryService", effective)`; `application.yml` `agent-tier-map` (lines 357-371) has no such entry, and `ModelResolver` defaults unmapped agents to `AgentTier.WORKHORSE` with a warn (`router/ModelResolver.java:63-64,194`). The class javadoc costs the feature at "~$0.0001 per file per generation **on Haiku** (LIGHTWEIGHT tier)" — at WORKHORSE (Sonnet/GPT-5.4) it's ~25× that, × ≤40 files × repos. Flag `codemap.purpose-summaries` is default OFF, so this fires the day someone flips it. Also note the call site hardcodes `provider=null → ANTHROPIC` (line 142) regardless of project provider (`CodeMapGenerator.java:223` passes `null`).

**[BUG] `RunCodeMap.routeCount` counts files and functions, not routes.**
`codeintel/RunCodeMapService.java:42` `ROUTE_LINE = "(?m)^- \`[^\`]+\`\s*$"` matches every `` - `...` `` line. Since 2026-05-16, `CodeMapGenerator.generate` emits up to 1,000 file-listing lines (`CodeMapGenerator.java:157`) and per-file function lines (line 233) in **exactly that shape**, so `countRoutes` (line 260) now returns files+routes+functions. The persisted `route_count` metric is garbage for any post-2026-05-16 codemap.

**[BUG] `syncRepo` never passes the caller PAT to stack detection, contradicting its own javadoc.**
`ProjectRepoService.java:216`: `stackDetector.detect(repo.getGithubRepoFullName(), null)` — the `overridePat` parameter, documented at line 203-204 as "used for the SHA + **stack** + subdir probes against private repos", is threaded into the SHA probe (line 218-222) and subdir probe (line 229-233) but not the stack probe. Private repos silently get `detected_stack` refresh failures on every sync. `createRepo` (line 150) has the same `null`-PAT detect (no PAT available on that signature, so size gate (line 111) also runs PAT-less — private-repo attach can't measure size either).

**[BUG] Ledger `run_number` assignment races.**
`memory/RunLedgerAgent.java:71`: `runNumber = ledgerRepo.countByProjectId(projectId) + 1` — two project runs completing concurrently both read the same count and write duplicate `run_number`s (no unique constraint guards it). Compaction windows (`coversRunsFrom/To`, `findUncompactedBeforeRunNumber`) assume monotone unique numbers, so collisions quietly skew snapshot ranges.

**[BUG] Dashboard "latest" score per run is actually the oldest.**
`project/dashboard/ProjectDashboardController.java:353-359` `latestScoreForRun` does `findFirst()` over `reportRepo.findAllByProjectId(...)`, and that query is `ORDER BY v.created_at ASC` (`report/ValidationReportRepository.java:25-35`). For any run with >1 validation report the dashboard's recent-task score shows the first/oldest one.

**[BUG] Code-RAG index never evicts deleted/renamed files; `.min.js` skip can't match.**
`project/rag/CodeIndexingService.java`: the incremental walk deletes+reinserts only files it visits (line 158); files removed or renamed in the repo keep their chunks forever (`indexedFileShas` is never reconciled against the walked set), so `search_code` returns code that no longer exists. Separately, `isBinaryName` (line 181-184) uses `lastIndexOf('.')`, so the `".min.js"` entry in `BINARY_EXT` (line 68) can never match (`app.min.js` → `.js`) — minified bundles get chunked + embedded.

**[BUG, self-documented] `RunCodeMapService.summarise` calls `AnthropicChatModel` directly.**
`codeintel/RunCodeMapService.java:237` — bypasses the router (failover/circuit breaker/`RunCostTracker`), is Anthropic-only (Gemini/OpenAI orgs get no summary), and its tokens never reach the run's cost. The class itself flags this as audit item E #35 "left as a follow-up" (lines 210-218) — it is still live. Pairs with the `@Autowired(required=false)` **field** injection at lines 51-52, which violates the project's constructor-injection rule (second instance: `GitHubWebhookController.java:44-45` `prCommentInboxService`).

### Risks

**[RISK] Workspace Redis lock: unlock is not atomic, and git failures are swallowed at DEBUG.**
`project/workspace/ProjectWorkspaceService.java:130-136,181-186` — release is GET-then-DEL (no Lua/CAS): between the GET and DEL the TTL can expire and another writer acquire, and we delete *their* lock. Token is `Long.toHexString(System.nanoTime())` — same-JVM collisions effectively impossible, cross-JVM merely unlikely. Worse: `runGit` (line 220-228) logs non-zero exits at **debug** and returns normally — `writeAndCommit` can "succeed" without the add/commit having happened (e.g. git missing, index lock), violating the method's contract silently. `initIfMissing` (line 68) runs before lock acquisition, so two first-writers can race `git init` + the initial commit. Also the file write itself is atomic (tmp + `ATOMIC_MOVE`) — that part of the claim holds.

**[RISK] EmbeddingService: no HTTP timeout, new client per batch, zero cost tracking, hardcoded model.**
`project/rag/EmbeddingService.java:62` builds a fresh `RestClient` per `callOnce` with no connect/read timeout — a hung OpenAI call blocks document indexing or, worse, the planner path (`RetrievalService.retrieve` is invoked from `RunOrchestrator` ~line 1138 and `SequentialExecutor:1904`); `RetrievalService` does catch and fail-open, but only after the hang. `MODEL = "text-embedding-3-small"` (line 34) is hardcoded — grep confirms no `embedding` key exists in `application.yml`, breaking the "YAML is the single source of truth for every model identifier" rule. No `RunCostTracker` call anywhere in `project/rag/` (grep-verified): all embedding spend (doc indexing, code indexing ≤4,000 files/repo, every retrieval query) is invisible to the dashboard rollup that claims to capture "every LLM dollar" (`ProjectDashboardController.java:51-53`). `embedQuery(...).get(0)` (line 58) throws IOOBE if the API returns an empty data array.

**[RISK] Staleness cache can be poisoned by a PAT-less first read.**
`codeintel/StalenessService.java:65` caches by projectId only; `compute` treats `current == null` (private repo probed without PAT) as `stale = false` (line 118-119). The first caller after expiry — possibly a PAT-less principal — caches "not stale" for 5 minutes for everyone. The javadoc's "first-authenticated-read wins" is actually first-*any*-read wins.

**[RISK] Cost misattribution on LLM failover in arch generation.**
`RepoArchitectureGenerator.java:123` and `ArchitectureDocGenerator.java:137` return `resolved.getModel()` as `modelUsed` — the *requested* model. `ArchLlmDispatch` (line 62-70) dispatches through `FallbackLlmRouter.callWithFallback(..., FallbackMode.IMMEDIATE, ...)`, which can serve from a different provider/model; `ProjectArchitectureService` then computes cost with `providerNameFor(provider)` — the *project-configured* provider (lines 241-248, 398-400) — while its comment claims "providerName() is the actual provider that served the call (after failover)". On failover the persisted `cost_usd` uses the wrong pricing row.

**[RISK] `projchat-<repoId>-<sha12>` clone directories accumulate without eviction.**
`CodeIndexingService.java:116` and `ProjectChatTools.java:537` mint a new full clone per repo per synced SHA; grep finds no cleanup path for these workspaces. Active multi-repo projects leak a full clone per HEAD-move on a disk that also hosts run workspaces.

**[RISK] Codemap "idempotent by SHA" has a TOCTOU seam.**
`CodeMapGenerator.generateWithSha` (line 108-111) snapshots the branch-head SHA *first*, then lists+reads files at whatever HEAD is by then; a push in between stamps new-tree content with the old SHA, and `ensureCodeMap`'s equality reuse (`ProjectArchitectureService.java:294-304`) will then treat stale content as current.

**[RISK] `ProjectMemoryProvider.MAX_MEMORY_TOKENS` is declared but never enforced.**
`memory/ProjectMemoryProvider.java:28` — `buildPlannerContext` concatenates snapshot + last 10 entries with no truncation; the 12k "budget" only appears in `getStats` for display. Snapshot size depends entirely on the compaction LLM honoring its prompt.

**[RISK] Synchronous multi-minute regen endpoints.**
`POST /api/projects/{id}/architecture/generate` and `/analyse` (`ProjectArchitectureController.java:57-83`) run the full R×(codemap+repo-arch) + combined + summary pipeline on the request thread. The codebase already learned this lesson for the post-merge cascade (Cloudflare 100s → P0 hotfix #3, `PostMergeCascadeService` javadoc) but kept the direct endpoint synchronous; a 3+ repo project with all SHAs moved can still 524 at the edge.

### Perf

**[PERF] Dashboard endpoint materializes every ValidationReport row.**
`ProjectDashboardController.java:183` `reportRepo.findAllByProjectId(projectId)` loads full entities (including `issues_json` blobs) just to average scores — the B4 comment (line 155-157) fixed the identical heap-scan for tokens/cost via DB SUMs but left the score path. Plus N+1 PR queries for 10 recent runs (line 208-213) and 6 separate COUNT queries (lines 138-143). `TechDebtService.build` (line 61-64) and `TechDebtFixService.collectIssuesForCategory` (line 108) do the same full-table materialization per request.

**[PERF] `PrRefreshService.refreshProject` is a 1+500-query sweep.**
`project/pr/PrRefreshService.java:92-112`: loads up to 500 runs, then issues one PR query **per run id**. A `findByRunIdIn(...)`/join would collapse it. Also line 193: once a PR has ever been CI-polled (`ciCheckedAt != null`), every subsequent sweep saves the row even when nothing changed.

**[PERF] Row-by-row vector inserts.** `ChunkStore.insertAll` (line 35-47) and `CodeChunkStore.insertFileChunks` (line 51-67) issue one `jdbc.update` per chunk — a 200-chunk document is 200 round-trips; `batchUpdate` is the obvious fix. (Both nearest-neighbor queries are fine: `<=>` cosine + ivfflat indexes exist in V95/V223.)

**[PERF] N+1 `repoRepo.findById` per link** repeated in 8 places: `ProjectWorkspaceWarmer.warmDiskCache`, `ProjectRepoService.listProjectRepos`/`resolveForRun`, `WorkspaceHealthService.report`, `StalenessService.compute`, `ProjectDocGeneratorService.buildSourceBundle` (twice), `ProjectChatTools.linkedRepos`/`listRepos`/`runCodeReview`, `ProjectArchitectureService.generate`. All bounded by repos-per-project caps — low individually, pervasive as a pattern.

### Inconsistencies

**[INCONSISTENT] Three different project-access implementations with diverging V175 semantics.**
`ProjectAccessGuard` (canonical — honors cross-org `project_member`); `ProjectRepoService.requireProjectAccess` (line 573-584 — org-match only: a V175 cross-org PROJECT_DEVELOPER who passes the controller's guard then 403s in the service for repo list/link); `SpecTemplateService.requireProjectAccess` (line 132-145 — same org-only check, so cross-org members can't use templates); `ProjectChatController.requireChatAccess` (line 149-178 — honors members per RL-2026-05-056). Behavior for cross-org collaborators depends on which surface they touch.

**[INCONSISTENT] Chat mutations have no write-gate.**
`ProjectChatController` uses the same `requireChatAccess` for `send` (paid LLM + write tools), `DELETE` (wipe history), and `/compact` (paid LLM + irreversible rewrite). The identical audit reasoning that flipped `ProjectMemoryController.compact` to `assertCanWrite` ("B #11 — PROJECT_VIEWER could drain project's LLM budget", `ProjectMemoryController.java:52-54`) was never applied here — a cross-org PROJECT_VIEWER member can clear chat history and spend the org's tokens.

**[INCONSISTENT] Score-trend feed exposes the LLM self-assessment the rest of the UI retired.**
`project/scores/ProjectScoresController.java:39` maps `overallScore ← getAccuracyScore()` with no verifiedScore fallback, while the dashboard's `preferredScore` (`ProjectDashboardController.java:369-374`) is verifiedScore-first. The trend chart and the dashboard headline can tell different stories about the same run.

**[INCONSISTENT] `TechDebtFixService` javadoc promises the TechDebtService lookback window; the code applies none.**
`project/techdebt/TechDebtFixService.java:37-38` vs `collectIssuesForCategory` (line 107-136), which reads ALL reports ever. Long-fixed issues from old reports keep landing in new fix prompts forever (deduped, never aged out).

**[INCONSISTENT] Prompt-handling convention.**
The codebase rule is external prompt files via `PromptAssembler`; this slice has zero `PromptAssembler` usage (grep-verified). `RunLedgerAgent`/`MemoryCompactionService` read `prompts/*.txt` via `ClassPathResource.getContentAsString` + manual `.replace` (the explicitly banned pattern); `RepoArchitectureGenerator`, `ArchitectureDocGenerator`, `ProjectDocGeneratorService`, `ProjectBriefService`, `FilePurposeSummaryService`, `RunCodeMapService`, `ProjectChatService` (3 prompts), `TechDebtFixService`, `CiFailureAutoFixService` all carry raw inline prompt strings.

**[INCONSISTENT] Provider fallback defaults disagree with the platform default.**
Null-provider fallbacks are `OPENAI` in `ProjectArchitectureService.providerNameFor`/`generate`/`regenerateForRepo`, `RepoArchitectureGenerator:91`, `ArchitectureDocGenerator:104`, `ProjectBriefService:83`, `ProjectDocGeneratorService:350`, `MemoryCompactionService:132` (hardcoded), `ProjectChatService.compactHistory:141`/`compileToSpec:252` — but `ANTHROPIC` in `ProjectChatService.send:337`, `ProjectChatController.info:75`, `FilePurposeSummaryService:142`. The platform default flipped to Anthropic in 1.0.0-M1; half these fallbacks predate it.

**[INCONSISTENT] Stale claims inside code.** `ProjectChatService` class javadoc (line 40-41) says "Read-only — no file tools, just talk" while `bind()` wires 15 tools including two write pipelines; `RepoArchitectureGenerator` javadoc (line 27-28) says "LIGHTWEIGHT tier (cheap, fast)" while `application.yml:362-363` maps both arch generators to PLANNING; `ProjectChatTools.runCodeReview` description (line 788-789) names the retired MAX tier; dashboard `costBreakdown` claims "every LLM dollar" but excludes project-chat tokens, embeddings, and the `RunCodeMapService` summarise call.

**[INCONSISTENT] `serializeList` emits invalid JSON for backslashes + frontend type lie.**
`ProjectRepoService.java:652-660` escapes `"` but not `\` — a relationship string ending in `\` produces an unparseable jsonb value (Postgres jsonb column would reject the INSERT). And `ProjectRepoLinkDto.relationships` is the raw JSON **String**, while `frontend/src/types/project.ts:43` types it `string[]` — currently harmless only because no frontend code reads it back (grep: only the PUT writer).

### Layering

**[LAYERING] `ProjectDashboardController` is a service in a controller's clothing** — 15+ injected repositories/services and ~100 lines of aggregation logic in the handler (`ProjectDashboardController.java:132-242`); the codebase's own convention is controller → service.
**[LAYERING] Browse-tab endpoints skip the repo→project binding.** `ProjectArchitectureController.listRepoFiles`/`readRepoFile` (lines 253-311) guard the projectId but use the path `repoId` directly without verifying it's linked to that project — any project-read principal can probe any registered repo UUID (bounded by their own PAT for private content; the `codemap`/`repoArchitecture` endpoints are naturally scoped by the composite query and don't have this gap).
**[LAYERING] `ProjectChatService` builds its large system prompt inline** (`buildMessages`, ~110 lines of string-appends incl. a lifecycle FAQ) instead of a prompt file — content drift here is invisible to prompt tooling.

## 3. Dead code & junk — grep-verified

- `project/pr/CiFailureAutoFixService.java:246-253` — `private String resolveCallerPat(...)`: **zero callers** anywhere (main/test/frontend). Dead.
- `project/rag/ChunkStore.java:84-86` — `public List<float[]> emptyVectorList()`: **zero callers**. Dead oddball utility.
- `memory/chattools/ProjectChatTools.java:772-778` — `requireProject(...)`: self-confessed dead (`@SuppressWarnings("unused")`, "kept for parity").
- `memory/repository/ProjectRunLedgerRepository.java:28` — `findByProjectIdAndRunId`: **zero callers** outside its declaration.
- `codeintel/repository/FilePurposeSummaryRepository.java:36` — `findByGeneratedAtBefore`: no live caller; staged for a sweeper that was deliberately deferred (documented in-line, lines 18-21, 33-35). Borderline-acceptable staging, but it has sat unwired since V196.
- `prompts/run-ledger.txt:29` — `{{DIFF_STAT}}` is always substituted with the hardcoded string "(diff stat unavailable — worker ran sequentially)" (`RunLedgerAgent.java:83`); the template var is vestigial.
- No `TODO`/`FIXME`/`HACK` markers exist anywhere in the three packages (grep) — junk here is of the dead-method kind, not the stale-comment kind, with the exception of the stale javadoc claims listed under INCONSISTENT.

## 4. Model/LLM touchpoints

| Site | Mechanism | Model/tier | Notes |
|---|---|---|---|
| `rag/EmbeddingService` | raw `RestClient` → `POST /v1/embeddings` | **hardcoded** `text-embedding-3-small` (1536d, matches `vector(1536)` in V95/V223) | bypasses router by design; no timeout; no cost tracking; YAML has no embedding entry |
| `codeintel/RunCodeMapService.summarise` | **direct** `AnthropicChatModel.call` | whatever the Anthropic bean defaults to | bypasses router/cost tracker; Anthropic-only; self-documented debt (audit E #35); `@Autowired` field |
| `RepoArchitectureGenerator` / `ArchitectureDocGenerator` | `ArchLlmDispatch` → `FallbackLlmRouter` (anthropic/openai/gemini) or `LlmRouter` (deepseek/openrouter), `MAX_OUTPUT_TOKENS=16000` | `agent-tier-map`: **PLANNING** (yml:362-363) — javadoc wrongly says LIGHTWEIGHT | `modelUsed`=requested model → cost misattribution on failover |
| `FilePurposeSummaryService` | `FallbackLlmRouter` | **unmapped in yml → WORKHORSE default** (ModelResolver:63-64); javadoc promises Haiku/LIGHTWEIGHT at ~$0.0001/file | provider hardcoded ANTHROPIC at call site; flag `codemap.purpose-summaries` default OFF |
| `ProjectBriefService.summarise` | `FallbackLlmRouter` | LIGHTWEIGHT (yml:361) | summary.md, runs once per arch regen |
| `ProjectDocGeneratorService` | `FallbackLlmRouter` | WORKHORSE (yml:364) | 15-40s call inside `@Transactional` |
| `RunLedgerAgent` | `FallbackLlmRouter` | LIGHTWEIGHT (yml:357) | prompt via raw `ClassPathResource` |
| `MemoryCompactionService` | `FallbackLlmRouter` | resolves under the **borrowed agent name "RunLedgerAgent"**; provider hardcoded `LlmProvider.OPENAI` | |
| `ProjectChatService.send` | `LlmRouter` manual tool loop, ≤6 rounds, temp 0.3, maxTokens 2048 | explicit `AgentTier.PLANNING` under name "ProjectChatAgent" | tokens persisted per message; NOT in dashboard cost rollup |
| `ProjectChatService.compactHistory` / `compileToSpec` | `LlmRouter`, tools off | "ProjectChatCompactor" LIGHTWEIGHT / "ProjectChatCompiler" PLANNING (yml:359-360) | |
| Arch regen cost per full pass (R repos, all changed, flags off) | — | — | R×(repo-arch PLANNING) + 1 combined PLANNING + 1 brief LIGHTWEIGHT; +≤40·R WORKHORSE calls if purpose-summaries flips on (see bug); + ≤54 GitHub API reads per repo from `CodeMapGenerator` (94 with summaries) |

Pricing/tokens: every site except the two bypasses funnels usage into `RunCostTracker.computeCost` and persists `cost_usd` on the artifact rows (V174 columns); embeddings and chat are the untracked gaps.

## 5. Libraries

Spring Boot (web/data-jpa/redis/scheduling/async) + Spring AI (`ChatResponse`/`ToolCallback`/messages — chat + generators), Jackson (manual `ObjectNode` envelope building throughout chat tools), Lombok (`@RequiredArgsConstructor`/`@Data`/`@Builder` per convention, mostly clean — two `@Autowired` field exceptions noted), jakarta.persistence, **JavaParser** (`JavaAstSymbolExtractor`, JAVA_21 level, static shared parser — thread-safety asserted by comment), **Apache Tika** (`AutoDetectParser` + unbounded `BodyContentHandler(-1)`, input capped at 10MB upstream), **ShedLock** (`StackRefreshJob`), Micrometer counters (`StackRefreshJob`), pgvector via raw `JdbcTemplate` (no Hibernate vector type — reasonable choice, correctly parameterized SQL). No suspicious or redundant deps in scope; hand-rolled JSON in `serializeList`/`serialiseFingerprint` is the only place Jackson should have been used and wasn't (fingerprint version is deliberate + tested; serializeList is buggy).

## 6. Test coverage

11 files / 1,531 LOC, all fast unit tests, quality of what exists is good (behavior-pinning, well-commented). Coverage map:
- **Well covered:** codemap symbol extraction (regex per language + AST extractor, caps, skip rules — 530 LOC across 2 files), arch fingerprint canonicalisation, doc-gen cache-hit short-circuit, FilePurposeSummary flag/cache/sha/truncate, CodeIndexing walk (skip rules + incremental SHA), ProjectAccessGuard decision tree, root.yml subdir parser, Expo/RN stack detection, FolderStatus normalisation, TieredCodemapSelector.
- **Zero coverage (notable given the bug density found):** the whole **`memory/` package** (chat service tool loop, compaction, ledger agent — 2,642 LOC, 0 tests); the whole **RAG pipeline** (`ChunkingService` — pure and trivially testable, `ChunkStore`, `RetrievalService`, `EmbeddingService`, `ProjectDocumentService` — the file with the two worst bugs); the whole **`pr/` subpackage** (webhook parsing/HMAC, PrRefreshService state machine, PostMergeCascade, CiFailureAutoFix cap logic, PullRequestRecordService); `ProjectWorkspaceService` (locking + atomic-write + traversal guards); `StalenessService`; `VersionRetentionService`; `ArchitectureDiffService`; `RunCodeMapService` (countRoutes regression would have been caught); dashboard aggregation; `TechDebtService`/`TechDebtFixService`; templates; estimation percentiles.

## 7. Security notes (parked)

- `GET /api/prs/snapshot` (`PullRequestController.java:110-120`) has **no project/run guard** — any authenticated user can use the server as a GitHub snapshot proxy for arbitrary `repo`+`number` (bounded by their own stored PAT / public repos).
- `ProjectArchitectureController.listRepoFiles`/`readRepoFile` don't verify the repoId is linked to the path projectId (see LAYERING) — cross-tenant repo metadata exposure requires guessing a UUID and content access is PAT-bound; low but real modeling gap.
- Project chat: cross-org PROJECT_VIEWER can `DELETE` chat history and trigger paid LLM calls (`send`/`compact`) — no write-gate (see INCONSISTENT).
- Deleted uploaded documents persist forever in the project workspace **git history** (`ProjectDocumentService.delete` soft-deletes the row + removes chunks but never touches `docs/<file>` or its `.md` extraction sibling, both committed by `writeAndCommit`) — a data-retention/GDPR consideration given the GDPR-export feature exists.
- `SpecTemplateService.delete`: the URL's projectId is decorative; authz is re-derived from the template's own owner (correct outcome, confusing trust boundary). Org-scoped template create/delete requires only org membership, not ORG_ADMIN.
- `StalenessService` serves results computed with one user's PAT to all project readers from cache (only SHA-level metadata; minor).
- Positive notes: GitHub webhook HMAC verification is correct (constant-time compare, blank-secret → reject-all); `ProjectWorkspaceService.resolve` path-traversal guard is component-wise correct; `readRepoFile` rejects `..`/absolute; `ChunkStore`/`CodeChunkStore` SQL fully parameterized; `parseRootYamlSubdir` sanity gates pinned by tests.
