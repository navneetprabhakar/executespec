# `codereview/` — Whole-repo Code Review agent

> Last reviewed: 2026-05-12 (1.0.0-M1). **Merged to dev/main.**
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

## Files

- `CodeReviewAgent.java` — single-call PLANNING-tier agent. Emits `submitCodeReview` tool call. Parser drops malformed issues + normalizes unknown source/severity values.
- `CodeReviewService.java` — orchestrator. Tier gate → idempotency → CLONING → STATIC_RUNNING (semgrep) → LLM_RUNNING (`CodeReviewAgent`) → COMPLETE / FAILED. SSE on `code-review-{scanId}`. Persists issues + markdown report atomically. Cost attribution via `RunCostTracker.snapshot("code-review-{id}")`.
- `CodeReviewController.java` — REST surface. `POST /api/code-reviews` returns 202 + QUEUED row. List, detail, latest-for-repo, and markdown download endpoints.
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
spots semgrep flagged.

Total walk capped at 5,000 files; per-file content capped at 12,000 chars. Skipped
entirely: `.git/`, `node_modules/`, `target/`, `build/`, `dist/`, `.gradle/`,
`.mvn/`, `.idea/`, `.vscode/`, `.lock` files, minified JS/CSS, binary assets.

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
returns the existing row.

## Outstanding (post-PR follow-ups)

- semgrep auto-installation in the prod GCE container (today: graceful fallback to
  LLM-only when not on PATH).
- Scheduled cron / post-merge auto-trigger (today: MANUAL only — the controller
  schema accepts `triggerSource ∈ {MANUAL, SCHEDULED, POST_MERGE}` so the column
  is forward-compatible).
- Per-issue resolve / dismiss / mark-as-false-positive flow on the dashboard
  (today: status defaults to OPEN; the column is in place).
