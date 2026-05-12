# ExecuteSpec — Feature Workflow Diagrams

> **Last updated:** 2026-05-12 (1.0.0-M1 · migration head V186).
> **Audience:** engineers + product + QA.
> User journeys per tier and per persona live in [`user-journeys.md`](user-journeys.md) + [`project-user-journeys.md`](project-user-journeys.md). This doc is the **Mermaid diagram surface** for every end-to-end workflow.

## 1. V2 BUILD-mode pipeline

```mermaid
sequenceDiagram
  participant U as Customer
  participant R as RunController
  participant O as RunOrchestrator
  participant C as SmartClassifierAgent
  participant P as OutlinePlannerAgent
  participant G as Plan Gate
  participant E as SequentialExecutor
  participant W as WorkerAgent
  participant V as ReviewerAgent
  participant S as ScmPushService
  participant GH as GitHub
  U->>R: POST /api/runs
  R->>O: submitRun(req)
  O->>C: classify(spec)
  C-->>O: PlanStrategy
  alt SINGLE_SHOT
    O->>O: synthesizeSingleShot (no LLM)
  else SMALL/MEDIUM
    O->>P: FULL_PLAN (32k cap)
    P-->>O: Plan
  else LARGE/LEGACY
    O->>P: SKELETON_ONLY (8k cap)
    P-->>O: skeleton
    O->>O: parallel TaskExpanderAgent x N
    O->>O: CoherencePlannerAgent (patchPlan)
  end
  O->>G: openPlanGate(runId)
  U->>R: POST /api/runs/{id}/approve
  R->>G: approve
  G-->>O: gateOpened
  loop for each task
    O->>E: executeOne(task)
    E->>W: run(task, ctx)
    W-->>E: outcome
    Note over E: smoke test (final task only)
  end
  O->>V: review(workspace, plan)
  V-->>O: verdict (CLEAN / FIXED / UNRESOLVED / REVIEW_FAILED)
  O->>O: compute verifiedScore
  O->>S: pushMultiRepo(workspace)
  S->>GH: branch + PR per linked repo
  GH-->>U: PR URL
  O-->>U: SSE COMPLETE {status, status_reason}
```

## 2. V2 PATCH-mode pipeline (4 autofix surfaces + SWE-bench / ProgramBench)

```mermaid
sequenceDiagram
  participant F as Finding source
  participant FS as FindingPatchSubmitter
  participant O as RunOrchestrator
  participant PA as PatchAgent
  participant T as RunTestsTool
  participant SC as ScmPushService
  F->>FS: submit(Finding)
  FS->>FS: dedupe by finding key
  FS->>O: submitRun(runMode=PATCH, source=finding:TYPE)
  O->>PA: runPatchPipeline
  loop until completeTask + test gate pass
    PA->>T: runTests
    T-->>PA: {summary, passed, failed, errored}
    PA->>PA: writeFile / editFile / shell
  end
  Note over PA: completeTask gate refuses if failed:[] errored:[] exit_code:0<br/>not within last 5 iters
  PA-->>O: terminal
  alt branchOverride present (Phase 1 PR Review autofix)
    O->>SC: push(workspace, branchOverride)
  else fresh PR (Phase 2/3/4)
    O->>SC: push(workspace, new branch)
  end
```

### Autofix sources

| Phase | Trigger | Source class |
|-------|---------|--------------|
| 1 | PR Review verdict popover → "Autofix N comments" | `prreview/PrReviewAutofixService` |
| 2 | TechDebtPanel category row → "Fix" | `project/techdebt/TechDebtFixService` |
| 3 | GitHub `check_run` webhook (flag-gated `ci.autofix.enabled`) | `project/pr/CiFailureAutoFixService` |
| 4 | CodeQualityPanel severity chip → "N CRITICAL" / "N MAJOR" | `codereview/CodeReviewAutofixService` |

## 3. Run status state machine

```mermaid
stateDiagram-v2
  [*] --> QUEUED
  QUEUED --> PLANNING
  PLANNING --> AWAITING_APPROVAL
  AWAITING_APPROVAL --> WORKING : approve()
  AWAITING_APPROVAL --> CANCELLED : cancel()
  WORKING --> REVIEWING
  WORKING --> PAUSED : pause() (flag v2.pause.enabled)
  PAUSED --> WORKING : resume()
  PAUSED --> CANCELLED : cancel()
  REVIEWING --> COMPLETE
  REVIEWING --> PARTIAL
  REVIEWING --> FAILED
  WORKING --> FAILED : exception
  COMPLETE --> [*]
  PARTIAL --> [*]
  FAILED --> [*]
  CANCELLED --> [*]
```

PARTIAL terminals carry one of these `status_reason` values: `CLEAN_COMPLETION` / `CLEAN_WITH_INTERMEDIATE_WARNINGS` / `FINAL_BUILD_FAILED` / `TESTS_FAILED:N_OF_M` / `REVIEWER_UNRESOLVED` / `REVIEWER_FAILED` / `ALL_TASKS_FAILED`. The `StatusBadge` tooltip surfaces a human-friendly version.

## 4. Multi-repo PR flow

```mermaid
sequenceDiagram
  participant W as WorkerAgent
  participant SC as ScmPushService.pushMultiRepo
  participant GH as GitHubRepoService
  participant DB as PullRequestRecordService
  Note over W: writes routed to workspace/<runId>/repos/<name>/
  W-->>SC: workspace ready
  loop for each linked repo
    SC->>GH: create branch executespec/<runId-short>
    SC->>GH: commit + push
    SC->>GH: openPullRequest(branch, body)
    GH-->>DB: persist PullRequestRecord(repoId, runId, prNumber)
  end
  SC->>GH: updatePullRequestBody (cross-link)
```

GitHub webhook flips status:

```mermaid
sequenceDiagram
  participant GH as GitHub
  participant W as GitHubWebhookController
  participant PR as PullRequestRecordService
  participant PM as PostMergeCascadeService
  GH->>W: POST /webhooks/github (HMAC SHA-256)
  W->>W: verify signature
  alt pull_request closed (merged)
    W->>PR: applyWebhookUpdate(MERGED)
    PR->>PM: writePostMergeBar(projectId, marker)
  else check_run completed
    W->>PR: applyWebhookUpdate(ci_status)
    alt ci.autofix.enabled + status=failure
      W->>FindingPatchSubmitter: submit CI failure finding
    end
  end
```

## 5. Cost + credit lifecycle

```mermaid
sequenceDiagram
  participant U as Customer
  participant O as RunOrchestrator
  participant CE as CostEstimator
  participant T as TierEnforcementService
  participant B as BillingService
  participant RCT as RunCostTracker
  U->>O: submitRun
  O->>CE: estimateRunTierAware
  CE-->>O: low/high credit band
  O->>O: persistRun (status=PLANNING)
  Note over O: classify + plan
  O->>O: persistEstimate (clamp hardCapUsd = min(platformCap, userCostCap))
  O->>U: SSE AWAITING_APPROVAL
  U->>O: approve(creditsConfirmed)
  O->>T: assertCanSubmit (refund safety)
  O->>B: chargeCredits(N)
  B-->>O: charged
  loop each LLM call
    O->>RCT: recordCall(tokens, cost)
    RCT->>RCT: enforce hardCapUsd
    alt over cap
      RCT-->>O: BUDGET_EXCEEDED (terminate worker)
    end
  end
  O->>O: persistTerminal
  alt status == FAILED
    O->>B: refundCredits(N)
  end
```

## 6. Project architecture pipeline (3-step)

```mermaid
sequenceDiagram
  participant U as Customer
  participant PC as ProjectArchitectureController
  participant CM as CodeMapGenerator
  participant RA as RepoArchitectureGenerator
  participant AD as ArchitectureDocGenerator
  U->>PC: POST /api/projects/{id}/architecture/generate
  loop for each linked repo
    PC->>CM: generate(repo, sha)
    Note over CM: idempotent — reuse if codemap.generatedFromSha == sha
    CM-->>PC: ProjectCodeMap
    PC->>RA: generate(codemap)
    Note over RA: idempotent — reuse if repoArch.sourceCodeMapId matches
    RA-->>PC: RepoArchitectureDoc (HLD/LLD)
  end
  PC->>AD: synthesize(perRepoDocs)
  AD-->>PC: ProjectArchitectureDoc (combined)
  PC->>U: SSE ARCH_READY
```

## 7. RAG indexing flow

```mermaid
sequenceDiagram
  participant U as Customer
  participant PDC as ProjectDocumentController
  participant DS as DocumentExtractor
  participant CS as ChunkingService
  participant ES as EmbeddingService
  participant CSt as ChunkStore (pgvector)
  U->>PDC: POST /api/projects/{id}/documents (file upload)
  PDC-->>U: status=UPLOADED
  PDC->>DS: extract (Tika)
  DS-->>PDC: text
  PDC-->>U: status=EXTRACTING
  PDC->>CS: chunks(500 / 50 overlap)
  CS-->>PDC: chunks
  PDC->>ES: embed each chunk (OpenAI text-embedding-3-small)
  ES-->>CSt: persist (pgvector)
  PDC-->>U: status=READY
  Note over CSt: RetrievalService.retrieve(projectId, query, k, cap)<br/>used by planner + chat
```

## 8. External project sharing (V175)

```mermaid
flowchart TB
  A[Project owner] -->|POST /api/projects/{id}/invites| B(ProjectInvite)
  B -->|email link| C[Invitee]
  C -->|GET /api/invites/preview?token=| D{Preview}
  D -->|POST /api/invites/accept| E[ProjectMember<br/>is_external=true<br/>role=PROJECT_VIEWER]
  E --> F[ProjectAccessGuard<br/>now allows cross-org read]
  G[Project owner] -->|POST /api/projects/{id}/share-links| H(ProjectShareLink<br/>surface, expiresAt)
  H -->|tokenised URL| I[Anonymous reader]
  I -->|GET /api/shared/{token}/...| J[Public read endpoint<br/>tier-gated active cap]
```

## 9. PR Review agent (V172, PRO+)

```mermaid
sequenceDiagram
  participant U as Customer
  participant PRC as PrReviewController
  participant PRS as PrReviewService
  participant PRA as PrReviewAgent
  participant GH as GitHubRepoService
  participant DB as pr_review + pr_review_comment
  U->>PRC: POST /api/pr-reviews
  PRC->>PRS: enqueue
  PRS-->>U: 202 + QUEUED row
  PRS->>GH: fetch PR diff + files
  GH-->>PRS: snapshot
  PRS->>PRA: review(snapshot)
  PRA-->>PRS: submitReview tool call (verdict + line-anchored comments)
  PRS->>DB: persist
  PRS-->>U: SSE COMPLETE
  Note over U: customer can click "Autofix N comments"<br/>→ FindingPatchSubmitter Phase 1
```

## 10. Code Review agent (V172, PRO+)

```mermaid
sequenceDiagram
  participant U as Customer
  participant CRC as CodeReviewController
  participant CRS as CodeReviewService
  participant SG as SemgrepRunner
  participant CRA as CodeReviewAgent
  participant MD as CodeReviewMarkdownRenderer
  U->>CRC: POST /api/code-reviews
  CRC->>CRS: enqueue
  CRS-->>U: 202 + QUEUED
  CRS->>CRS: clone workspace
  CRS->>SG: semgrep --config p/ci
  SG-->>CRS: findings (or empty if semgrep missing)
  CRS->>CRA: triage(findings + curated 20-file sample)
  CRA-->>CRS: KEEP/REWRITE/DROP per finding + LLM additions
  CRS->>MD: render markdown report
  MD-->>CRS: report_markdown
  CRS-->>U: SSE COMPLETE (download via /report)
```

## 11. Plan-gate auto-cancel on JVM restart

```mermaid
sequenceDiagram
  participant Boot as Spring boot
  participant SR as StartupRunResumer
  participant DB as PipelineRunRepository
  participant CTX as ContextStore (Redis)
  Boot->>SR: ApplicationReadyEvent
  SR->>DB: findNonTerminalRuns()
  loop each interrupted run
    alt status == AWAITING_APPROVAL
      SR->>SR: leave alone
    else status in {PLANNING, WORKING, REVIEWING}
      SR->>CTX: load snapshot
      alt snapshot fresh + 3-attempt cap not exceeded
        SR->>RunOrchestrator: resumeAfterCrash(runId)
      else stale or exhausted
        SR->>DB: mark FAILED reason="recovery_exhausted"
      end
    end
  end
```

## 12. Preview lifecycle

```mermaid
sequenceDiagram
  participant O as RunOrchestrator (on terminal)
  participant PS as PreviewService
  participant CR as Cloud Run
  participant DC as Docker Compose (local)
  O->>PS: requestPreview(runId, projectType)
  alt PreviewProperties.mode == cloud-run
    PS->>CR: deploy image (5-min TTL)
    CR-->>PS: URL
  else mode == docker
    PS->>DC: docker compose up -d (Caddy proxy)
    DC-->>PS: URL
  end
  PS-->>O: previewUrl
  O-->>U: SSE PREVIEW_READY
  Note over PS: scheduled cleanup after TTL
```

## 13. Cancel rollback

```mermaid
sequenceDiagram
  participant U as Customer
  participant RC as RunController
  participant O as RunOrchestrator
  participant GH as GitHubRepoService
  participant SSE as SseBroadcaster
  U->>RC: DELETE /api/runs/{id}
  RC->>O: cancel(runId)
  O->>O: signal plan gate / interrupt pipeline future
  O->>O: status = CANCELLED
  loop each opened PR for this run
    O->>GH: closePullRequest(repo, prNumber)
    O->>SSE: emit EXEC_COMPENSATION_UPDATE (RUNNING / COMPLETE / FAILED)
  end
  O->>SSE: emit EXEC_COMPENSATION_DONE
  SSE-->>U: terminal SSE
```

## 14. Slack run-completion notification

```mermaid
sequenceDiagram
  participant O as RunOrchestrator
  participant RCN as RunCompletionNotifier
  participant SNS as SlackNotificationService
  participant SL as Slack
  O->>RCN: PipelineEvent.terminal(runId, status)
  RCN->>SNS: notify(orgId, projectId, run)
  SNS->>SNS: resolve project channel
  SNS->>SL: postMessage(blocks)
  SL-->>U: in-Slack notification
```

## 15. SSE event stream

```mermaid
sequenceDiagram
  participant U as Customer (browser)
  participant RC as RunController
  participant J as JwtUtil.issueSseToken
  participant SB as SseBroadcaster
  U->>RC: GET /api/runs/{id}/sse-token (authed)
  RC->>J: issueSseToken(runId, scope=sse, ttl=5min)
  J-->>U: scoped JWT
  U->>RC: GET /api/runs/{id}/events?token=<jwt>
  RC->>RC: verify scope == sse and runId match
  loop until terminal
    SB-->>U: PLAN_READY / RUN_STATUS / TASK_STATUS / LIVE_LOG / RUN_COST / EXEC_COMPENSATION_* / COMPLETE
  end
```

## 16. Per-task retry (manual)

```mermaid
sequenceDiagram
  participant U as Customer
  participant RC as RunController
  participant E as SequentialExecutor
  participant W as WorkerAgent
  U->>RC: POST /api/runs/{id}/tasks/{taskId}/retry
  RC->>E: retryTask (requireBuildVerification=true)
  E->>W: run with build-error injected
  W-->>E: outcome
  Note over E: smoke test must pass on retry attempt
  E-->>RC: status
  RC-->>U: 200
  Note over U: button "Update PR" available on InlineRunDetail<br/>(retry does NOT auto-push by itself)
```

## References

- Architecture: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md).
- User flows: [`docs/USER_MANUAL.md`](USER_MANUAL.md) + [`docs/user-journeys.md`](user-journeys.md) + [`docs/project-user-journeys.md`](project-user-journeys.md).
- API surface: [`docs/openapi.yaml`](openapi.yaml).
