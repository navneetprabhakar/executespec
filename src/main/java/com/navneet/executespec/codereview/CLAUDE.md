# `codereview/` — Whole-repo Code Review agent

The authenticated Markdown report download is a publication boundary: high-confidence credential
material fails closed with 422 and the matched value is never returned.

> Last reviewed: 2026-07-17. **Current worktree contract; not yet released.**
>
> Paid-tier feature (FREE → 403; PRO=4/month, TEAM/ENTERPRISE=unlimited). Schema in V172.
> Phase 4 of the [`finding/FindingPatchSubmitter`](../finding/) autofix surfaces — `CodeReviewAutofixService` dispatches a patch-mode run per severity bucket on demand.

## What it does

Two-stage whole-repo review:

1. **Static** — `SemgrepRunner` runs `semgrep --config p/ci` against the cloned
   workspace. Multi-language out of the box (Java / Python / TS / JS / Go / Rust /
   Ruby / C# / PHP / Kotlin / Swift / etc). Graceful degradation: if `semgrep`
   isn't on PATH the runner returns `{available=false}` and the LLM pass runs
   alone — that's still more useful than no scan.
2. **LLM augmentation** — `CodeReviewAgent` (PLANNING-tier, 16K cap) takes the
   static findings + a curated 20-file sample (controllers / auth / db / config /
   `.sql` / `.yml` first, capped at 12K chars each) and:
   - **Triages** static findings: KEEP / REWRITE / DROP (false positive)
   - **Adds** issues semgrep can't see — architectural mistakes, subtle logic
     errors, security issues that need code-comprehension, perf issues with
     business-logic context, missing tests for risky code

Output: structured `code_review_issue` rows + a single markdown report rendered
by `CodeReviewMarkdownRenderer` (severity-grouped, file-anchored, suggested-fix
blocks). The markdown is the chat surface's primary handoff to the user — same
shape as `ProjectGeneratedDoc` / `RepoArchitectureDoc`.

## V292 Project Chat confirmed route

`RUN_CODE_REVIEW` can also be started from the explicit Web or CLI/OpenTUI Project Chat action.
This is not a model-generated proposal: the human selects the linked `repoId`, sees the
action-specific route and budget, and confirms it. The server validates that provider/model against
the current host-project policy with Enterprise org matrix overrides bound, then consumes a hashed,
actor/org/project/action/target/route/budget-scoped bearer before scheduling the scan.

`CodeReviewService.submitConfirmedScan` accepts the exact provider/model and one shared
`LlmExecutionReference`. It propagates the route into the asynchronous executor with
`RunModelOverrides` and clears it afterward. `CodeReviewAgent.AgentRequest` repeats the expected
model and rejects provider/model drift before dispatch, so the async boundary cannot silently
switch provider, step models, or fail over. Usage is attributed to the initiating project member;
credential resolution stays bound to the host project subject. The confirmed-action cap is 300,000
total tokens, $5, and two provider calls. Failure to construct or honor the exact route returns 503,
and the already-consumed grant remains burned.

## Files

- `CodeReviewAgent.java` — single-call PLANNING-tier agent. Emits `submitCodeReview` tool call. Parser drops malformed issues + normalizes unknown source/severity values.
- `CodeReviewService.java` — orchestrator. Tier gate → idempotency → CLONING → STATIC_RUNNING (semgrep) → LLM_RUNNING (`CodeReviewAgent`) → COMPLETE / FAILED. SSE on `code-review-{scanId}`. Persists issues + markdown report atomically. Cost attribution via `RunCostTracker.snapshot("code-review-{id}")`.
- `CodeReviewController.java` — REST surface. `POST /api/code-reviews` requires a linked `repoId`, write-guards the project, and replaces the caller's `repoFullName` with the canonical durable identity before tier/quota checks. Direct detail/report reads are project-read-guarded. List, latest-for-repo, and markdown download endpoints remain available to authorised project readers.
- `SemgrepRunner.java` — wraps the `semgrep` subprocess. Configurable via `codereview.semgrep.{ruleset,timeout-seconds,binary}` properties; default `p/ci`. Streams stdout/stderr concurrently to avoid pipe-buffer deadlock; tolerates exit codes 0 (no findings) and 1 (findings present), warns + returns empty on 2+.
- `CodeReviewAgent.java` — single-call PLANNING-tier agent. Emits `submitCodeReview` tool call.
- `CodeReviewMarkdownRenderer.java` — pure formatter. No LLM calls, no DB queries. Severity-grouped (CRITICAL → MAJOR → MINOR → INFO), file-anchored, with suggested-fix code blocks.
- `entity/CodeReviewScan.java`, `entity/CodeReviewIssue.java` — JPA entities for `code_review_scan` + `code_review_issue` (V172).
- `repository/CodeReviewScanRepository.java`, `repository/CodeReviewIssueRepository.java` — Spring Data JPA repos. `countByUserSince` powers the monthly-quota gate; `findFirstByProjectIdAndRepoFullNameAndStatusOrderByStartedAtDesc("COMPLETE")` is the chat tool's canonical "latest" lookup.

## File sampling heuristic

`CodeReviewService.scoreFile(path, content)` ranks files by risk so we get the most
useful 20 in the LLM's prompt without paying for a full-repo upload:

- `+30` controllers / auth / security
- `+25` `.sql` files
- `+20` services
- `+15` repositories / config
- `+10` `/api/`
- `-30` test files
- `-20` markdown
- `-10` files under 200 chars

Files referenced by static findings get a `+50` bump so the LLM sees the actual hot
spots semgrep flagged. The bump is applied at admission, not after the walk, so the
bounded retention below still produces the old ranking.

Total walk capped at 5,000 files; per-file content capped at 12,000 chars. Skipped
entirely: `.git/`, `node_modules/`, `target/`, `build/`, `dist/`, `.gradle/`,
`.mvn/`, `.idea/`, `.vscode/`, `.lock` files, minified JS/CSS, binary assets.

### Bounded retention (RR-433)

The sampler used to decode every eligible file into a String and hold all of them
until after the walk — 5,000 files x 1 MB was a multi-gigabyte live-heap ceiling
before any provider call, and concurrent reviews could take the JVM down with
unrelated runs on it. Now:

- each candidate is streamed once, retaining at most `MAX_BYTES_PER_FILE` characters
  while counting every line, so LOC no longer requires materializing whole files;
- retention is a bounded top-K heap of `MAX_FILES_IN_PROMPT` already-truncated,
  already-redacted excerpts (~240 KB), independent of repository size;
- a cumulative 256 MB read budget is checked against `BasicFileAttributes.size()`
  BEFORE each file is opened, plus a 120s walk deadline. Hitting either stops the
  walk and logs a warning; `totalFiles` / `totalLoc` are then a lower bound.

### Scan-coverage honesty (RR-1685)

A lower bound that is stated as a fact is a false claim, and the review prompt
stated `File count: N / Total LOC: M` with no qualifier. `SampleResult.coverage`
now records WHY the bounded walk ended — `COMPLETE`, `TRUNCATED_FILE_LIMIT`,
`TRUNCATED_READ_BUDGET`, `TRUNCATED_TIME_BUDGET`, `TRUNCATED_WALK_FAILED` — and
`CodeReviewAgent.AgentRequest.scanCoverage` carries it into `composeUserPrompt`,
which renders `Scanned file count: N (LOWER BOUND — not the whole repository)`
plus an explicit `Coverage:` line whenever the walk did not finish. A **null**
coverage is rendered as `UNKNOWN` and still marked as a lower bound, because "we
did not record whether the scan finished" is not "the scan finished". A
non-directory workspace is `TRUNCATED_WALK_FAILED`, so an unreadable clone is
never reported as a fully measured empty repository. Same family as RR-1657 /
RR-1658. Pinned by `CodeReviewScanCoverageTest`.

Also note the counts measure different populations: `totalFiles` counts every
non-skipped file under the per-file size cap, while `totalLoc` counts lines only
of the language-recognised files actually read — hence the prompt now says
"Scanned file count" and "Lines of code read" rather than implying a repository
total. **Not covered:** `code_review_scan.total_files_scanned` /
`total_loc` and the `CodeReviewController` DTO still persist and expose the bare
numbers with no coverage marker; that needs a migration and is out of scope here.

### Credential boundary (RR-435)

Raw config and source used to reach the review model verbatim, and the score
heuristic explicitly boosted `.env`. Prompt-injection wrapping is not
confidentiality. Now, reusing the platform primitives rather than a new redactor:

- `SensitiveWorkspacePathPolicy` excludes `.env`, credential stores, private keys and
  keystores from `shouldSkip` outright (`.env.example` and declared templates stay
  inspectable), and drops static findings anchored on those paths;
- `GeneratedSecretScanner.redactRecognizedSecrets` runs over every retained excerpt
  before it is held in the candidate set, and over every semgrep snippet and message
  before it is mapped into `CodeReviewAgent.StaticFinding`;
- the `.env` term is gone from `scoreFile`; `docker` / `config` remain.

**Surface minimisation + disclosure audit (RR-435 follow-up, 2026-07-21):**

- a file flagged by a static finding is now sent as bounded line WINDOWS around each
  finding anchor (`readBoundedWindows`, `FINDING_CONTEXT_LINES = 40`, overlapping
  windows merged, each line keeps its 1-based number) instead of the whole
  `MAX_BYTES_PER_FILE` head, so a large flagged file discloses only the relevant
  neighbourhood. Non-anchored files keep the head read;
- `sampleFiles(root, findings, auditKey)` writes a SERVER-LOG-ONLY audit line
  (`auditDisclosure`) naming every file and line range that crossed the boundary to
  the review model plus the char count — never into the prompt, so the disclosure is
  auditable after the fact without becoming a new disclosure channel.

**Bound on the claim (unchanged — RR-435 stays PARTIAL):** redaction covers only
high-confidence provider/PAT/cloud/PEM formats and the declared sensitive-path set.
Pattern-based redaction cannot catch a secret in an unrecognised format, there is no
general PII redactor, and reviewing a repository still discloses its source to the
configured provider. This work REDUCES the disclosed surface and ADDS an audit trail;
it does not close disclosure.

## Schema

**V172** creates:

```sql
code_review_scan
  ├── status ∈ {QUEUED, CLONING, STATIC_RUNNING, LLM_RUNNING, COMPLETE, FAILED}
  ├── trigger_source ∈ {MANUAL, SCHEDULED, POST_MERGE}
  ├── total_files_scanned + total_loc + static_findings_count + llm_findings_count + llm_filtered_out_count
  ├── cost_usd + total_input_tokens + total_output_tokens
  └── report_markdown TEXT          -- chat surface + download endpoint use this verbatim

code_review_issue
  ├── fingerprint VARCHAR(64)        -- sha256(rule || file || line || msg-prefix || severity)
  ├── source ∈ {STATIC, LLM, HYBRID} -- HYBRID = static surfaced, LLM rewrote
  ├── severity ∈ {CRITICAL, MAJOR, MINOR, INFO}
  ├── status ∈ {OPEN, RESOLVED, DISMISSED, FALSE_POSITIVE}
  ├── rule_id, file_path, line_start, line_end, message, suggested_fix
  └── FK code_review_scan (CASCADE)
```

## Idempotency

`CodeReviewService.submitScan` short-circuits when a COMPLETE scan exists for
`(projectId, repoFullName, scannedSha)`. Re-running on the same HEAD is safe and
returns the existing row. The service no longer falls back to cloning an arbitrary caller-supplied
GitHub full name if the admitted `repoId` disappears or changes identity before dispatch; it fails
the scan instead.

## Outstanding (post-PR follow-ups)

`CodeReviewService` has an explicit Spring-selected production constructor in addition to its
package-private compatibility constructor. Keep the production constructor explicit whenever the
dependency list changes; Lombok does not generate `@RequiredArgsConstructor` when another
constructor already exists. The benchmark-profile boot gate caught this contract on 2026-07-18.

- semgrep auto-installation in the prod GCE container (today: graceful fallback to
  LLM-only when not on PATH).
- Scheduled cron / post-merge auto-trigger (today: MANUAL only — the controller
  schema accepts `triggerSource ∈ {MANUAL, SCHEDULED, POST_MERGE}` so the column
  is forward-compatible).
- Per-issue resolve / dismiss / mark-as-false-positive flow on the dashboard
  (today: status defaults to OPEN; the column is in place).
