# JAVADOC

> Compact reference of the public Java surface for ExecuteSpec backend. Version `1.0.0-M1`. Migration head `V208`. All paths are relative to `src/main/java/com/navneet/executespec/` unless otherwise noted. Grep-friendly: one entity / method / class per line where possible.
>
> ⚠️ **Partially stale** — head reference refreshed 2026-05-24, but the last comprehensive body rewrite was v2.4.0 (2026-04-14). For anything not found here, the authoritative surface is `CLAUDE.md` + per-package `CLAUDE.md` + `docs/CODEMAP.md`. Full rewrite is queued (`docs/tech-debt.md`).

## 1. Controllers (REST surface)

### Run lifecycle

#### `api/controller/RunController` (base `/api/runs`)
Lifecycle hub for V2 pipeline runs — submit, list, get, approve, cancel, archive, soft-delete, fork, retry, sync, push, workspace browse, plan-versions, task-graph. Removed-in-V2 endpoints return `410 Gone`.
```
POST   /                                  submit(AppUserDetails, RunRequest) → RunSummary (202)
GET    /                                  list(AppUserDetails, status?, projectId?, scope?, archived=false, page=0, size=20) → Map<String,Object>
GET    /{runId}                           get(runId, AppUserDetails) → RunDetail
GET    /{runId}/cost                      getLiveCost(runId, AppUserDetails) → Map<String,Object>
POST   /{runId}/approve                   approvePlan(runId, ApprovalRequest, AppUserDetails) → Void
POST   /{runId}/clarify                   410 Gone
POST   /{runId}/merge                     410 Gone
POST   /{runId}/retry                     410 Gone
POST   /{runId}/fork                      forkRun(runId, AppUserDetails, ForkOverrides?) → RunSummary
POST   /{runId}/pause                     pauseRun(runId, AppUserDetails) → Void
POST   /{runId}/edit-and-restart          410 Gone
POST   /{runId}/resume                    resumeRun(runId, AppUserDetails) → Void
POST   /{runId}/push-github               pushToGitHub(AppUserDetails, runId, repoUrl?, repoMode=NEW) → Map<String,String>  @Deprecated
POST   /{runId}/sync                      syncRun(AppUserDetails, runId, branch?) → Map<String,Object>
POST   /{runId}/sync/original             syncRunToOriginal(AppUserDetails, runId) → Map<String,Object>
POST   /{runId}/push-scm                  pushToScm(AppUserDetails, runId, repoUrl?, repoMode=EXISTING) → Map<String,String>
PATCH  /{runId}/project                   updateRunProject(runId, Map<String,String>, AppUserDetails) → RunSummary
DELETE /{runId}                           cancel(runId, AppUserDetails) → Void
POST   /{runId}/archive                   archiveRun(runId, AppUserDetails) → Map<String,String>
POST   /{runId}/unarchive                 unarchiveRun(runId, AppUserDetails) → Map<String,String>
POST   /{runId}/soft-delete               softDeleteRun(runId, AppUserDetails) → Map<String,String>
POST   /{runId}/restore                   restoreRun(runId, AppUserDetails) → Map<String,String>
POST   /{runId}/tasks/{taskId}/retry      retryTask(runId, taskId, AppUserDetails) → Map<String,Object>
POST   /{runId}/implement-suggestions     410 Gone
POST   /{runId}/rework                    410 Gone
POST   /{runId}/reopen-merge              410 Gone
POST   /{runId}/retry-validation          retryValidation(runId, AppUserDetails) → Void
GET    /{runId}/recovery                  getRecovery(runId, AppUserDetails) → List<RecoveryResponse>
GET    /{runId}/messages                  getMessages(runId, AppUserDetails) → List<MessageResponse>
GET    /{id}/download                     downloadWorkspace(id, format=zip, AppUserDetails) → ResponseEntity<?>
GET    /{id}/files                        listWorkspaceFiles(id, AppUserDetails) → List<String>
GET    /{id}/files/status                 listWorkspaceFilesWithStatus(id, AppUserDetails) → List<WorkspaceFileService.FileEntry>
GET    /{runId}/openapi.yaml              getOpenApiSpec(runId, AppUserDetails) → String
GET    /{id}/file                         readWorkspaceFile(id, path, AppUserDetails) → String
GET    /{id}/file/diff                    readWorkspaceFileDiff(id, path, AppUserDetails) → FileDiff
GET    /{runId}/plan-versions             getPlanVersions(runId, AppUserDetails) → List<PlanVersion>
POST   /{runId}/edit-plan                 410 Gone
GET    /{runId}/task-graph                getTaskGraph(runId, AppUserDetails) → List<TaskGraphNode>
```

#### `api/controller/SseController` (base `/api/runs`)
SSE token issuance + run event stream.
```
GET    /{runId}/sse-token                 getSseToken(runId, AppUserDetails) → Map<String,String>
GET    /{runId}/events                    subscribe(runId, token?, Last-Event-ID?, AppUserDetails) → SseEmitter  (text/event-stream)
```

#### `api/controller/RunConversationController` (base `/api/runs`)
Post-run chat + async apply-suggestion dispatch.
```
GET    /{runId}/chat                      getHistory(runId, AppUserDetails) → List<ChatHistoryItem>
POST   /{runId}/chat                      sendMessage(runId, ChatMessageBody, AppUserDetails) → ChatResponseBody
POST   /{runId}/apply                     applySuggestion(runId, ApplyBody, AppUserDetails) → CompletableFuture<ApplyResponseBody>
GET    /{runId}/apply/status              applyStatus(runId, AppUserDetails) → ApplyResultStore.ApplyState
POST   /{runId}/iterate                   410 Gone
```

#### `api/controller/PreviewController` (base `/api/runs`)
Preview lifecycle + in-container exec + API-docs probe.
```
GET    /{runId}/preview/analyze           analyze(runId, AppUserDetails) → PreviewAnalysisDto
GET    /{runId}/preview/status            getStatus(runId, AppUserDetails) → PreviewStatusDto
POST   /{runId}/preview/start             start(runId, PreviewStartRequest?, AppUserDetails) → PreviewStatusDto
POST   /{runId}/preview/rebuild           rebuild(runId, AppUserDetails) → PreviewStatusDto
DELETE /{runId}/preview/stop              stop(runId, AppUserDetails) → Void
GET    /{runId}/preview/logs              getLogs(runId, lines=0, filter=all, AppUserDetails) → PreviewLogsDto
GET    /{runId}/preview/probe-api-docs    probeApiDocs(runId, AppUserDetails) → ApiDocsProbeResult
POST   /{runId}/preview/exec              execInContainer(runId, ExecRequest, AppUserDetails) → ExecResponse
```

#### `api/controller/TimelineController` (base `/api`)
```
GET    /runs/{runId}/timeline             timeline(runId, AppUserDetails) → TimelineData
GET    /model-comparison                  modelComparison(days=30, AppUserDetails) → ModelComparisonData
```

#### `api/controller/UserRunsController` (base `/api`)
```
GET    /user-runs-summary                 userRunsSummary(days=30, AppUserDetails) → UserRunsSummaryData
```

#### `api/controller/MetricsController` (base `/api/metrics`)
```
GET    /                                  metrics(days=0, AppUserDetails) → MetricsResponse
```

#### `api/controller/FeasibilityController` (base `/api/feasibility`)
```
POST   /                                  estimate(FeasibilityRequest) → 410 Gone  (V1 retired)
```

#### `api/controller/ConversationController` (base `/api/conversation`)
Pre-flight requirement-refinement chat (all tiers).
```
POST   /                                  chat(ConversationRequest, AppUserDetails) → ConversationResponse
```

### Project workbench

#### `auth/controller/ProjectController` (base `/api/projects`)
```
POST   /                                  createProject(AppUserDetails, CreateProjectRequest) → ProjectDto (201)
GET    /                                  listProjects(AppUserDetails) → List<ProjectDto>
GET    /{id}                              getProject(id, AppUserDetails) → ProjectDto
PATCH  /{id}/settings                     updateSettings(id, UpdateProjectSettingsRequest, AppUserDetails) → ProjectDto
PUT    /{id}/archive                      archive(id, AppUserDetails) → ProjectDto
PUT    /{id}/unarchive                    unarchive(id, AppUserDetails) → ProjectDto
DELETE /{id}                              softDelete(id, AppUserDetails) → Void
POST   /{id}/restore                      restore(id, AppUserDetails) → ProjectDto
GET    /{id}/members                      listMembers(id, AppUserDetails) → List<ProjectMemberDto>
POST   /{id}/members                      addMember(id, Map<String,String>, AppUserDetails) → Void (201)
DELETE /{id}/members/{userId}             removeMember(id, userId, AppUserDetails) → Void
```

#### `project/dashboard/ProjectDashboardController` (no base)
```
GET    /api/projects/{projectId}/dashboard          dashboard(projectId, AppUserDetails) → DashboardDto
GET    /api/projects/{projectId}/workspace-health   workspaceHealth(projectId, AppUserDetails) → WorkspaceHealthService.Report
GET    /api/projects/{projectId}/post-merge-bar     postMergeBars(projectId, AppUserDetails) → List<PostMergeBar>
GET    /api/projects/{projectId}/estimate           estimate(projectId, requirement?, AppUserDetails) → Estimate
GET    /api/projects/{projectId}/tech-debt          techDebt(projectId, since?, AppUserDetails) → TechDebtService.Report
POST   /api/projects/{projectId}/tech-debt/fix      fixTechDebt(projectId, FixTechDebtRequest, AppUserDetails) → FixTechDebtResponse (202)
DELETE /api/projects/{projectId}/post-merge-bar     dismissPostMergeBars(projectId, AppUserDetails) → Void
```

#### `codeintel/ProjectArchitectureController` (no base)
```
POST   /api/projects/{projectId}/architecture/generate  generate(projectId, AppUserDetails) → ArchDocDto
POST   /api/projects/{projectId}/analyse                analyse(projectId, branch?, AppUserDetails) → ArchDocDto
GET    /api/projects/{projectId}/summary                summary(projectId, AppUserDetails) → Map<String,String>
GET    /api/projects/{projectId}/architecture/staleness staleness(projectId, AppUserDetails) → StalenessReport
GET    /api/projects/{projectId}/architecture           latest(projectId, AppUserDetails) → ArchDocDto
GET    /api/projects/{projectId}/architecture/versions  versions(projectId, AppUserDetails) → List<ArchDocDto>
GET    /api/projects/{projectId}/architecture/diff      diff(projectId, from, to, AppUserDetails) → DiffReport
PUT    /api/projects/{projectId}/architecture/annotate  annotate(projectId, Map<String,String>, AppUserDetails) → ArchDocDto
GET    /api/projects/{projectId}/repos/{repoId}/codemap        codemap(projectId, repoId, AppUserDetails) → CodeMapDto
GET    /api/projects/{projectId}/repos/{repoId}/architecture   repoArch(projectId, repoId, AppUserDetails) → RepoArchDto
GET    /api/projects/{projectId}/repos/{repoId}/files          listRepoFiles(projectId, repoId, AppUserDetails) → RepoFileListDto
GET    /api/projects/{projectId}/repos/{repoId}/file           readRepoFile(projectId, repoId, path, AppUserDetails) → RepoFileDto
```

#### `codeintel/ProjectDocGeneratorController` (no base)
```
POST   /api/projects/{projectId}/docs/{docType}/generate   generate(projectId, docType, AppUserDetails) → GeneratedDocDto (201)
GET    /api/projects/{projectId}/docs/{docType}            latest(projectId, docType, AppUserDetails) → GeneratedDocDto
GET    /api/projects/{projectId}/docs/{docType}/download   download(projectId, docType, AppUserDetails) → String (text/markdown)
```

#### `project/controller/ProjectRepoController` (no base)
```
GET    /api/repos/github                                listGitHubRepos(AppUserDetails, owner?) → List<RemoteRepo>
POST   /api/repos                                       createRepo(AppUserDetails, CreateRepoRequest) → ProjectRepoDto (201)
GET    /api/repos                                       listAvailable(AppUserDetails, scope?) → List<ProjectRepoDto>
POST   /api/repos/{repoId}/sync                         syncRepo(AppUserDetails, repoId) → ProjectRepoDto
POST   /api/repos/{repoId}/webhook                      installWebhook(AppUserDetails, repoId) → Map<String,Object>
DELETE /api/repos/{repoId}                              deleteRepo(AppUserDetails, repoId) → Void
GET    /api/projects/{projectId}/repos                  listProjectRepos(AppUserDetails, projectId) → List<ProjectRepoLinkDto>
POST   /api/projects/{projectId}/repos/{repoId}         linkToProject(AppUserDetails, projectId, repoId) → ProjectRepoLinkDto (201)
DELETE /api/projects/{projectId}/repos/{repoId}         unlinkFromProject(AppUserDetails, projectId, repoId) → Void
PUT    /api/projects/{projectId}/repos/{repoId}/relationships  updateRelationships(AppUserDetails, projectId, repoId, UpdateRelationshipsRequest) → ProjectRepoLinkDto
```

#### `project/template/SpecTemplateController` (no base)
```
GET    /api/projects/{projectId}/templates                       listForProject(projectId, AppUserDetails) → List<TemplateDto>
POST   /api/projects/{projectId}/templates                       createForProject(projectId, CreateRequest, AppUserDetails) → TemplateDto
POST   /api/orgs/{orgId}/templates                               createForOrg(orgId, CreateRequest, AppUserDetails) → TemplateDto
DELETE /api/projects/{projectId}/templates/{templateId}          delete(projectId, templateId, AppUserDetails) → Void
```

#### `project/scores/ProjectScoresController` (no base)
```
GET    /api/projects/{projectId}/scores                 scores(projectId, AppUserDetails) → List<ScoreEntry>
```

#### `project/rag/ProjectDocumentController` (base `/api/projects/{projectId}/documents`)
```
POST   /                                  upload(projectId, MultipartFile, AppUserDetails) → ProjectDocument (202)
GET    /                                  list(projectId, AppUserDetails) → List<ProjectDocument>
DELETE /{documentId}                      delete(projectId, documentId, AppUserDetails) → Map<String,String>
```

#### `memory/ProjectMemoryController` (base `/api/projects/{projectId}/memory`)
```
GET    /                                  getMemory(projectId, AppUserDetails) → ProjectMemoryResponse
GET    /ledger                            getLedger(projectId, AppUserDetails) → List<ProjectRunLedger>
POST   /compact                           compact(projectId, AppUserDetails) → Map<String,Object>
```

#### `memory/ProjectChatController` (base `/api/projects/{projectId}/chat`)
```
GET    /                                  history(AppUserDetails, projectId) → List<ProjectChatMessage>
GET    /info                              info(AppUserDetails, projectId) → ChatInfo
POST   /                                  send(AppUserDetails, projectId, ChatRequest) → ProjectChatMessage
POST   /compile                           compile(AppUserDetails, projectId) → Map<String,String>
DELETE /                                  clear(AppUserDetails, projectId) → Map<String,Integer>
POST   /compact                           compact(AppUserDetails, projectId) → CompactResult
```

#### `project/pr/PullRequestController` (no base)
```
GET    /api/runs/{runId}/prs                            listRunPrs(runId, AppUserDetails) → List<PrSummary>
POST   /api/projects/{projectId}/prs/refresh            refresh(projectId, forceAll=false, AppUserDetails) → PrRefreshService.RefreshResult
GET    /api/prs/snapshot                                snapshot(AppUserDetails, repo, number, maxChars=4000) → PullRequestSnapshot
```

#### `project/pr/GitHubWebhookController` (no base)
```
POST   /webhooks/github                   webhook(X-Hub-Signature-256, X-GitHub-Event, body) → Void
```

### Execution View (Launches)

#### `execution/controller/ExecutionController` (no base)
```
POST   /api/projects/{projectId}/executions                                   createExecution(projectId, CreateExecutionRequest, AppUserDetails) → ExecutionRunDto
GET    /api/executions/{id}                                                   getExecution(id, AppUserDetails) → ExecutionRunDto
GET    /api/projects/{projectId}/executions                                   listExecutions(projectId, page, size, AppUserDetails) → Page<ExecutionRunDto>
POST   /api/executions/{id}/analyse                                           triggerAnalysis(id, AppUserDetails) → Void (202)
PUT    /api/executions/{id}/arch-review                                       approveArch(id, ExecutionArchApprovalRequest, AppUserDetails) → Void
POST   /api/executions/{id}/plan                                              generatePlan(id, AppUserDetails) → Void (202)
PUT    /api/executions/{id}/plan/approve                                      approvePlan(id, AppUserDetails) → Void (202)
POST   /api/executions/{id}/solutions/{solutionId}/decide                     decide(id, solutionId, DecisionRequest, AppUserDetails) → Void (202)
POST   /api/executions/{id}/solutions/{solutionId}/manual-ready               manualReady(id, solutionId, AppUserDetails) → Void (202)
POST   /api/executions/{id}/resume                                            resume(id, AppUserDetails) → Void
POST   /api/executions/{id}/abort                                             abort(id, AppUserDetails) → Void
GET    /api/executions/{id}/solutions/{solutionId}/runs                       solutionRuns(id, solutionId, AppUserDetails) → List<ExecutionSolutionRun>
GET    /api/executions/{id}/report                                            getReport(id, AppUserDetails) → Map<String,Object>
GET    /api/projects/{projectId}/prs/{prNumber}/reviews                       prReviewComments(projectId, prNumber, repo, AppUserDetails) → Map<String,String>
GET    /api/executions/{id}/sse-token                                         sseToken(id, AppUserDetails) → Map<String,String>
GET    /api/executions/{id}/events                                            subscribe(id, token, Last-Event-ID?, AppUserDetails) → SseEmitter
```

#### `execution/controller/ProjectLaunchController` (no base)
```
GET    /api/projects/{projectId}/launches                       listLaunches(projectId, page, size, AppUserDetails) → Page<ExecutionRunDto>
GET    /api/projects/{projectId}/prs                            projectPrs(projectId, AppUserDetails) → List<PrRollupEntry>
GET    /api/projects/{projectId}/overview                       overview(projectId, AppUserDetails) → ProjectOverview
POST   /api/projects/{projectId}/launches/validate-repo-access  validateOwner(projectId, ValidateOwnerRequest, AppUserDetails) → ValidateOwnerResult
```

#### `execution/controller/ScaffoldController` (no base)
```
GET    /api/scaffolds                     list() → List<Scaffold>
```

### Code Review + PR Review

#### `codereview/CodeReviewController` (no base)
```
POST   /api/code-reviews                                              submit(SubmitCodeReviewRequest, AppUserDetails) → CodeReviewScanDto (202)
POST   /api/code-reviews/{id}/autofix                                 autofix(id, AutofixRequest, AppUserDetails) → AutofixResponse (202)
GET    /api/code-reviews/{id}                                         get(id, AppUserDetails) → CodeReviewScanWithIssues
GET    /api/projects/{projectId}/code-reviews                         listForProject(projectId, AppUserDetails) → List<CodeReviewScanDto>
GET    /api/projects/{projectId}/code-reviews/latest                  latest(projectId, repo, AppUserDetails) → CodeReviewScanWithIssues
GET    /api/code-reviews/{id}/report                                  report(id, AppUserDetails) → String (text/markdown)
```

#### `prreview/PrReviewController` (no base)
```
POST   /api/pr-reviews                                  submit(AppUserDetails, SubmitPrReviewRequest) → PrReviewDto (202)
POST   /api/pr-reviews/{id}/autofix                     autofix(id, AppUserDetails) → AutofixResponse (202)
GET    /api/pr-reviews/{id}                             get(id, AppUserDetails) → PrReviewWithComments
GET    /api/pr-reviews                                  listForPr(AppUserDetails, repo, number) → List<PrReviewDto>
GET    /api/projects/{projectId}/pr-reviews             listForProject(projectId, AppUserDetails) → List<PrReviewDto>
```

#### `prreview/inbox/PrCommentInboxController` (base `/api/runs/{runId}/pr-comments`)
```
GET    /                                  list(runId, AppUserDetails) → List<Dto>
GET    /pending-count                     pendingCount(runId, AppUserDetails) → PendingCount
POST   /{commentId}/dismiss               dismiss(runId, commentId, AppUserDetails) → Dto
POST   /{commentId}/apply                 apply(runId, commentId, AppUserDetails) → ApplyResponse (202)
```

### Contracts / ADRs / Standards / Invariants / Activity

#### `contracts/drift/ContractDriftController` (base `/api/projects/{projectId}`)
```
POST   /contracts/{contractId}/check-drift                check(projectId, contractId, AppUserDetails) → DriftDetectionService.DriftCheckResult
POST   /repos/{repoId}/check-drift                        checkRepo(projectId, repoId, AppUserDetails) → List<DriftCheckResult>
POST   /check-drift                                       checkProject(projectId, AppUserDetails) → List<DriftCheckResult>
POST   /contracts/{contractId}/refresh-from-source        refresh(projectId, contractId, AppUserDetails) → RefreshResponse
POST   /contracts/{contractId}/dismiss-drift              dismiss(projectId, contractId, DismissRequest, AppUserDetails) → DismissResponse
```

#### `contracts/consumers/ContractConsumerController` (base `/api/projects/{projectId}/contracts/{contractId}/consumers`)
```
GET    /                                  list(projectId, contractId, AppUserDetails) → List<ConsumerDto>
POST   /                                  declare(projectId, contractId, DeclareRequest, AppUserDetails) → ConsumerDto
DELETE /{consumerRepoId}                  remove(projectId, contractId, consumerRepoId, AppUserDetails) → Void (204)
```

#### `contracts/coverage/ContractCoverageController` (base `/api/projects/{projectId}/repos/{repoId}/contract-coverage`)
```
GET    /                                  coverage(projectId, repoId, AppUserDetails) → CoverageResponse
POST   /rescan                            rescan(projectId, repoId, AppUserDetails) → CoverageResponse
```

#### `adr/AdrController` (base `/api/projects/{projectId}/adrs`)
```
GET    /                                  list(projectId, acceptedOnly=false, auth) → List<AdrSummary>
GET    /{id}                              get(projectId, id, auth) → AdrDetail
```

#### `standards/OrgStandardController` (base `/api/orgs/{orgId}/standards`) [ORG_ADMIN/SUPER_ADMIN]
```
GET    /                                  list(AppUserDetails, orgId) → List<StandardDto>
GET    /{standardId}                      get(AppUserDetails, orgId, standardId) → StandardDto
POST   /                                  create(AppUserDetails, orgId, StandardUpsert) → StandardDto (201)
PUT    /{standardId}                      update(AppUserDetails, orgId, standardId, StandardUpsert) → StandardDto
DELETE /{standardId}                      delete(AppUserDetails, orgId, standardId) → Void (204)
```

#### `standards/override/StandardsOverrideController` (base `/api/projects/{projectId}/standards`)
```
POST   /violations/{violationId}/override     emergencyOverride(projectId, violationId, OverrideRequest, auth, AppUserDetails)
POST   /{standardId}/exception                addProjectException(projectId, standardId, ExceptionRequest, auth, AppUserDetails)
DELETE /{standardId}/exception                removeProjectException(projectId, standardId, auth, AppUserDetails)
```

#### `standards/report/StandardsComplianceController` (base `/api/projects/{projectId}/standards`)
```
GET    /report                            report(projectId, orgId, sinceDays=30, auth) → ComplianceReport
```

#### `arch/invariants/InvariantController` (base `/api/projects/{projectId}/invariants`)
```
GET    /                                  list(projectId, auth) → List<InvariantSummary>
GET    /violations                        recentViolations(projectId, auth) → List<ViolationDto>
```

#### `activity/ActivityFeedController` (base `/api/projects/{projectId}/activity`)
```
GET    /                                  list(projectId, sinceDays=30, eventTypes?, actorId?, ..., auth) → ActivityPage
```

### Sharing / Invites / Migrations / Readymades / Library

#### `sharing/controller/ProjectInviteController` (no base)
```
POST   /api/projects/{projectId}/invites                  create(projectId, CreateInviteRequest, AppUserDetails) → ProjectInviteDto
GET    /api/projects/{projectId}/invites                  listPending(projectId, AppUserDetails) → List<ProjectInviteDto>
DELETE /api/projects/{projectId}/invites/{inviteId}       revoke(projectId, inviteId, AppUserDetails) → Void
POST   /api/invites/accept                                accept(token, AppUserDetails) → AcceptResponse
GET    /api/invites/preview                               preview(token) → InvitePreview
```

#### `sharing/controller/ProjectShareLinkController` (no base)
```
POST   /api/projects/{projectId}/share-links              create(projectId, CreateShareLinkRequest, AppUserDetails) → ShareLinkDto
GET    /api/projects/{projectId}/share-links              list(projectId, AppUserDetails) → ShareLinkListResponse
DELETE /api/projects/{projectId}/share-links/{linkId}     revoke(projectId, linkId, AppUserDetails) → Void
```

#### `sharing/controller/ProjectSharePublicController` (no base, public — no auth)
```
GET    /api/shared/{token}/meta                  meta(token) → SharedLinkMeta
GET    /api/shared/{token}/run                   run(token) → SharedRun
GET    /api/shared/{token}/validation-report     validationReport(token) → SharedValidationReport
GET    /api/shared/{token}/code-review           codeReview(token) → SharedCodeReview
GET    /api/shared/{token}/pr-review             prReview(token) → SharedPrReview
GET    /api/shared/{token}/architecture          architecture(token) → SharedArchitecture
```

#### `migration/controller/MigrationController` (base `/api/migrations`)
```
GET    /                                  list(category?, tier?, search?, AppUserDetails) → List<MigrationListItemDto>
GET    /{slug}                            detail(slug, AppUserDetails) → MigrationDetailDto
POST   /{slug}/dry-run                    dryRun(slug, DryRunRequest, AppUserDetails) → MigrationDryRunDto
POST   /{slug}/apply                      apply(slug, ApplyRequest, AppUserDetails) → Map<String,Object> (202)
```

#### `migration/controller/MigrationAdminController` (base `/api/admin/migrations`) [SUPER_ADMIN]
```
POST   /reseed                            reseed() → Map<String,Object>
```

#### `readymade/controller/ReadymadeController` (base `/api/library`)
```
GET    /                                  list(category?, macro_group?, tier?, search?, AppUserDetails) → List<ReadymadeListItemDto>
GET    /{slug}                            detail(slug, AppUserDetails) → ReadymadeDetailDto
GET    /categories                        facets() → Map<String,Object>
POST   /{slug}/resolve                    resolve(slug, projectId, repoId) → VariantResolutionDto
POST   /{slug}/instantiate                instantiate(slug, InstantiateRequest, AppUserDetails) → Map<String,Object> (202)
POST   /favourite/{slug}                  favourite(slug, AppUserDetails) → Void (204)
DELETE /favourite/{slug}                  unfavourite(slug, AppUserDetails) → Void (204)
```

#### `readymade/controller/ReadymadeAdminController` (base `/api/admin/readymades`) [SUPER_ADMIN]
```
POST   /reseed                            reseed() → Map<String,Object>
```

### Auth / Org / SSO / DSAR / Admin

#### `auth/controller/AuthController` (base `/api/auth`)
```
POST   /register                          register(RegisterRequest) → UserDto (201)
POST   /login                             login(LoginRequest, response) → LoginResponse
POST   /refresh                           refresh(cookie, response) → LoginResponse
DELETE /logout                            logout(cookie, response) → Void
GET    /verify-email                      verifyEmail(token, response) → LoginResponse
POST   /resend-verification               resendVerification(...) → Map<String,String>
POST   /forgot-password                   forgotPassword(...) → Map<String,String>
POST   /reset-password                    resetPassword(...) → Map<String,String>
POST   /change-password                   changePassword(ChangePasswordRequest, AppUserDetails) → Map<String,String>
GET    /me                                me(AppUserDetails) → UserDto
POST   /invalidate-sessions               invalidateSessions(AppUserDetails) → Map<String,String>
GET    /me/permissions                    permissions(AppUserDetails) → TierPermissionService.TierPermissions
GET    /me/data-export                    dataExport(AppUserDetails) → byte[] (application/zip)
DELETE /me/data                           deleteData(AppUserDetails) → Map<String,Object>
PUT    /settings/github                   setGithub(GitHubSettingsRequest, AppUserDetails) → UserDto
PUT    /settings/bitbucket                setBitbucket(...) → UserDto
PUT    /settings/ai-keys                  setAiKeys(...) → UserDto
PUT    /settings/jira                     setJira(...) → UserDto
PUT    /settings/confluence               setConfluence(...) → UserDto
PUT    /settings/view-mode                setViewMode(ViewModeRequest, AppUserDetails) → UserDto
POST   /settings/ai-keys/validate         validateAiKey(ValidateAiKeyRequest) → ValidationResult
POST   /settings/github/validate          validateGithub(...) → ValidationResult
POST   /settings/bitbucket/validate       validateBitbucket(...) → ValidationResult
POST   /settings/jira/validate            validateJira(...) → ValidationResult
POST   /settings/confluence/validate      validateConfluence(...) → ValidationResult
GET    /generate                          bcryptHelper(password) → String  [test helper]
```

#### `auth/controller/OrgController` (base `/api/org`)
```
POST   /                                  createOrg(AppUserDetails, CreateOrgRequest) → OrgDto (201)
GET    /me                                getMyOrg(AppUserDetails) → OrgDto
PUT    /me                                updateOrg(AppUserDetails, Map<String,Object>) → OrgDto       [ORG_ADMIN]
GET    /me/members                        listMembers(AppUserDetails) → List<OrgMemberDto>
DELETE /me/members/{userId}               removeMember(AppUserDetails, userId) → Void                  [ORG_ADMIN]
PUT    /me/members/{userId}/suspend       suspendMember(AppUserDetails, userId, Map<String,Boolean>) → Void  [ORG_ADMIN]
PUT    /me/members/{userId}/role          changeRole(AppUserDetails, userId, Map<String,String>) → Void  [ORG_ADMIN]
POST   /invite                            invite(AppUserDetails, InviteRequest) → Map<String,String> (201)  [ORG_ADMIN]
GET    /me/invitations                    listInvitations(AppUserDetails) → List<OrgInvitation>
DELETE /me/invitations/{id}               cancelInvitation(id) → Void
POST   /me/invitations/{id}/resend        resendInvitation(AppUserDetails, id) → Map<String,String>
POST   /accept-invite                     acceptInvite(token, AppUserDetails) → Map<String,String>
GET    /me/audit-logs                     audit(AppUserDetails, filters..., page, size) → AuditLogPage   [ORG_ADMIN]
PUT    /me/settings/key-source            setKeySource(AppUserDetails, KeySourceSettingsRequest) → OrgDto
PUT    /me/settings/external-sharing      setExternalSharing(AppUserDetails, ExternalSharingRequest) → OrgDto  [ORG_ADMIN]
PUT    /me/settings/ai-keys               setAiKeys(AppUserDetails, OrgAiKeySettingsRequest) → OrgDto
PUT    /me/settings/jira                  setJira(AppUserDetails, OrgJiraSettingsRequest) → OrgDto
POST   /me/settings/jira/validate         validateJira(OrgJiraSettingsRequest) → ValidationResult
PUT    /me/settings/confluence            setConfluence(...) → OrgDto
POST   /me/settings/confluence/validate   validateConfluence(...) → ValidationResult
POST   /me/seats/adjust                   adjustSeats(AppUserDetails, SeatsAdjustRequest) → SeatsAdjustResponse  [ORG_ADMIN]
```

#### `auth/sso/SsoConfigController` (base `/api/auth/sso`) [SUPER_ADMIN/ORG_ADMIN]
```
GET    /config                            getConfig(AppUserDetails) → SsoConfigDto
PUT    /config                            updateConfig(AppUserDetails, SsoConfigUpdate) → SsoConfigDto
DELETE /config                            deleteConfig(AppUserDetails) → Void
```

#### `auth/dsar/DsarController` (base `/api/dsar`)
```
GET    /me                                exportMe(AppUserDetails) → Map<String,Object>
GET    /users/{userId}                    exportUser(AppUserDetails, userId) → Map<String,Object>  [SUPER_ADMIN]
```

#### `auth/controller/SuperAdminController` (base `/api/admin`) [SUPER_ADMIN]
```
GET    /guardrail                                       guardrail() → Map<String,Object>
POST   /executions/reconcile                            reconcile(Map<String,Object>) → ReconcileReport
POST   /validation-reports/backfill-verified-score      backfillVerifiedScore() → BackfillResult
POST   /runs/{runId}/tasks/{taskId}/force-fail          forceFailTask(runId, taskId, body, principal) → Map<String,Object>
POST   /runs/{runId}/re-review                          reReview(runId, principal) → Map<String,Object> (202)
GET    /audit-chain/verify                              verifyChain() → ActivityAuditService.ChainVerifyReport
POST   /runs/halt-all                                   haltAll(principal, body) → Map<String,Object>
POST   /prompts/{name}/rollback                         rollbackPrompt(name) → Map<String,Object>
GET    /prompts/{name}/history                          promptHistory(name) → List<PromptAuditEntry>
POST   /usage-reports/trigger                           triggerUsageReports(body) → Map<String,Object>
GET    /users                                           listUsers() → List<UserDto>
GET    /users/{id}                                      getUser(id) → UserDto
PUT    /users/{id}/status                               setUserStatus(principal, id, body) → UserDto
PUT    /users/{id}/tier                                 setUserTier(principal, id, body) → UserDto
PUT    /users/{id}/role                                 setUserRole(principal, id, body) → UserDto
GET    /orgs                                            listOrgs() → List<OrgDto>
GET    /orgs/{id}                                       getOrg(id) → OrgDto
PUT    /orgs/{id}/status                                setOrgStatus(principal, id, body) → OrgDto
PUT    /orgs/{id}/tier                                  setOrgTier(principal, id, body) → OrgDto
PUT    /orgs/{id}/run-limit                             setRunLimit(principal, id, body) → OrgDto
PUT    /orgs/{id}/api-keys                              setOrgApiKeys(principal, id, body) → OrgDto
GET    /tier-config                                     listTierConfigs() → List<TierConfig>
PUT    /tier-config/{tier}                              updateTierConfig(tier, Map<String,Integer>) → TierConfig
POST   /invite                                          adminInvite(principal, AdminInviteRequest) → Map<String,String>
GET    /invitations/escalated                           listEscalated() → List<OrgInvitation>
POST   /invitations/{id}/approve                        approveEscalation(id) → Map<String,String>
POST   /invitations/{id}/reject                         rejectEscalation(id) → Map<String,String>
GET    /audit-logs                                      auditLogs(filters..., page, size) → AuditLogPage
POST   /test-email                                      testEmail(body) → Map<String,String>
POST   /users/{id}/grant-runs                           grantUserRuns(principal, id, body) → RunTopup
POST   /orgs/{id}/grant-runs                            grantOrgRuns(principal, id, body) → RunTopup
GET    /topup-packages                                  listTopups() → List<TopupPackage>
POST   /topup-packages                                  createTopup(body) → TopupPackage
PUT    /topup-packages/{id}                             updateTopup(id, body) → TopupPackage
DELETE /topup-packages/{id}                             deleteTopup(id) → Void
```

#### `auth/controller/AdminReminderController` (base `/api/internal/reminder`)
```
POST   /send                              send(X-Admin-Reminder-Token, ReminderRequest) → Map<String,Object>
```

#### `folder/ExecutespecMigrationController` (base `/api/admin/projects`) [SUPER_ADMIN]
```
POST   /{projectId}/migrate-codemaps      migrate(projectId, auth) → MigrateResponse
```

#### `eval/EvalController` (base `/api/admin/eval`) [SUPER_ADMIN]
```
POST   /run                               runEvaluation(providers) → Map<String,Object>
```

#### `repo/GitHubAppController` (base `/api/auth/github-app`)
```
GET    /install-url                       installUrl(AppUserDetails) → Map<String,String>  [SUPER_ADMIN/ORG_ADMIN]
GET    /status                            status(AppUserDetails) → Map<String,Object>
POST   /callback                          callback(AppUserDetails, InstallCallback) → Map<String,Object>
DELETE /install                           uninstall(AppUserDetails) → Void
GET    /install                           installRedirect(AppUserDetails) → RedirectView
```

#### `swebench/SwebenchController` (base `/api/admin/swebench`) [SUPER_ADMIN]
```
POST   /run                               submit(SubmitRequest) → TaskSubmitResult
GET    /preview/{instanceId}              preview(instanceId) → Map<String,Object>
GET    /instances                         listInstances() → Map<String,Object>
```

#### `programbench/ProgramBenchController` (base `/api/admin/programbench`) [SUPER_ADMIN]
```
POST   /run                               submit(SubmitRequest) → TaskSubmitResult
GET    /preview/{taskId}                  preview(taskId) → Map<String,String>
```

### Billing / Invoices / Accounting

#### `billing/controller/BillingController` (base `/api/billing`)
```
GET    /plans                             listPlans(keyMode?, includeHidden=false, AppUserDetails) → List<PlanResponse>
GET    /founder-pricing/{tier}            founderPricing(tier) → FounderOfferDto
GET    /status                            status(AppUserDetails) → BillingStatusResponse
POST   /subscribe                         subscribe(CreateSubscriptionRequest, AppUserDetails) → SubscriptionInitResult
POST   /verify                            verify(VerifyPaymentRequest, AppUserDetails) → BillingStatusResponse
POST   /cancel                            cancel(AppUserDetails) → Void
POST   /seats                             updateSeats(UpdateSeatsRequest, AppUserDetails) → BillingStatusResponse
GET    /topup/packages                    getPackages() → List<TopupPackage>
POST   /topup/purchase                    initiatePurchase(TopupPurchaseRequest, AppUserDetails) → TopupOrderInitResult
POST   /topup/verify                      verifyTopup(TopupVerifyRequest, AppUserDetails) → BillingStatusResponse
GET    /profile                           getProfile(principal) → BillingProfile
PUT    /profile                           updateProfile(principal, BillingProfile) → BillingProfile
GET    /invoices                          listInvoices(principal, Pageable) → Page<Invoice>
GET    /invoices/{id}                     getInvoice(principal, id) → InvoiceDetail
GET    /invoices/{id}/pdf                 invoicePdf(principal, id) → ResponseEntity<?>
GET    /payment-history                   paymentHistory(orgId, page, size) → Page<PaymentAudit>   [ORG_ADMIN/BILLING_ADMIN/SUPER_ADMIN]
```

#### `billing/controller/BillingWebhookController` (base `/api/webhooks`)
```
POST   /razorpay                          razorpayWebhook(body, X-Razorpay-Signature) → ResponseEntity<?>
POST   /stripe                            stripeWebhook(body, Stripe-Signature) → ResponseEntity<?>
```

#### `billing/controller/InvoiceController` (no base, admin paths)
```
GET    /api/admin/invoices                listAll(principal, Pageable) → Page<Invoice>
GET    /api/admin/invoices/{id}/pdf       adminInvoicePdf(principal, id) → byte[]
POST   /api/admin/invoices/{id}/credit-note   createCreditNote(principal, id, CreditNoteRequest) → Invoice
POST   /api/admin/invoices/{id}/void          voidInvoice(principal, id) → Invoice
POST   /api/admin/invoices/{id}/send          resendInvoice(principal, id) → Void
GET    /api/admin/seller-config           getSellerConfig(principal) → SellerConfig
PUT    /api/admin/seller-config           updateSellerConfig(principal, SellerConfig) → SellerConfig
```

#### `billing/controller/PaymentAuditController` (no base) [SUPER_ADMIN]
```
GET    /api/admin/payment-audit           paymentAudit(page, size, orgId?, userId?) → Page<PaymentAudit>
GET    /api/admin/payment-audit/export    exportCsv() → byte[]
GET    /api/admin/webhook-events          webhookEvents(page, size, gateway?) → Page<WebhookEventLog>
```

#### `billing/controller/AccountingController` (base `/api/accounting`) [FINANCE]
```
GET    /revenue                           revenue(principal, from, to) → RevenueMetrics
GET    /gst-summary                       gstSummary(principal, fy, quarter) → GstSummary
GET    /gstr1-export                      gstr1Export(principal, fy, quarter) → byte[] (CSV)
GET    /trial-balance                     trialBalance(principal, from, to) → List<TrialBalanceEntry>
GET    /entries                           entries(principal, from, to, Pageable) → Page<AccountingEntry>
```

#### `billing/controller/AdminBillingController` (base `/api/admin/billing`)
```
POST   /seed-plans                        seedPlans() → ResponseEntity<String>
```

#### `billing/lifecycle/TierLifecycleController` (base `/api/admin`) [SUPER_ADMIN]
```
GET    /tiers                                            listAllTiers() → List<TierLifecycleDto>
PUT    /tiers/{tier}/lifecycle                           transitionTier(AppUserDetails, tier, LifecycleUpdateRequest) → TierConfig
PUT    /billing-plans/{planId}/lifecycle                 transitionPlan(AppUserDetails, planId, PlanLifecycleUpdateRequest) → BillingPlan
```

#### `api/controller/PricingController` (base `/api/pricing`)
```
GET    /                                  list(AppUserDetails) → List<PricingRow>
GET    /{provider}/{model}                get(provider, model, AppUserDetails) → PricingRow
PUT    /{provider}/{model}                upsert(provider, model, PricingUpdateRequest, AppUserDetails) → PricingRow  [SUPER_ADMIN]
```

#### `api/controller/DeploymentController` (base `/api/deployments`) [`agent.deployment.enabled=true`]
```
POST   /                                  deploy(DeployRequest, AppUserDetails) → DeploymentSummary
GET    /                                  list(runId?, AppUserDetails) → List<DeploymentSummary>
GET    /{id}                              detail(id, AppUserDetails) → DeploymentDetail
DELETE /{id}                              delete(id, AppUserDetails) → Void
POST   /{id}/rollback                     rollback(id, AppUserDetails) → Void
GET    /live                              live(AppUserDetails) → List<DeploymentSummary>
POST   /analyze                           analyze(runId, AppUserDetails) → ResponseEntity<?>
POST   /confirm                           confirm(DeployConfirmRequest, AppUserDetails) → DeploymentSummary
POST   /{id}/engine-rollback              engineRollback(id, AppUserDetails) → Void
DELETE /{id}/engine-destroy               engineDestroy(id, AppUserDetails) → Void
```

#### `deployment/credential/CredentialController` (base `/api/credentials/gcp`)
```
GET    /required-permissions              required() → List<RequiredPermission>
POST   /                                  store(AppUserDetails, projectId?, json) → CredentialSummary
GET    /                                  get(AppUserDetails, projectId?) → CredentialSummary
GET    /all                               listAll(AppUserDetails) → List<CredentialSummary>
GET    /validate                          validate(AppUserDetails) → ValidationResult
DELETE /                                  delete(AppUserDetails) → Map<String,String>
```

#### `deployment/preview/vm/PreviewVmController` (base `/api/v1/previews`) [`preview.mode=vm`]
```
DELETE /{previewId}                       teardown(previewId) → Void
POST   /{previewId}/extend                extend(previewId, minutes) → Void
GET    /active                            listActive() → List<PreviewRegistry>
GET    /stats                             stats() → Map<String,Object>
GET    /stats/vm                          vmStats() → List<Map<String,Object>>
```

### Notifications / Health / Integrations

#### `notification/NotificationController` (base `/api/notifications`)
```
GET    /                                  list(AppUserDetails, page=0, size=20) → Map<String,Object>
GET    /unread-count                      unread(AppUserDetails) → Map<String,Long>
POST   /{id}/read                         markRead(id, AppUserDetails) → Void
POST   /read-all                          markAllRead(AppUserDetails) → Void
```

#### `notification/pref/NotificationPrefController` (base `/api/notifications/preferences`)
```
GET    /                                  list(AppUserDetails) → List<PrefRow>
PUT    /                                  update(AppUserDetails, PrefUpdate) → PrefRow
```

#### `api/controller/HealthController` (base `/api/health`)
```
GET    /                                  health() → Map<String,Object>
GET    /ready                             ready() → Map<String,Object>
```

#### `api/controller/SlackIntegrationController` (base `/api/integrations/slack`)
```
GET    /install                           getInstallUrl(AppUserDetails) → Map<String,String>
GET    /oauth/callback                    oauthCallback(code, state) → Void (302)
DELETE /disconnect                        disconnect(AppUserDetails) → Map<String,String>
GET    /status                            status(AppUserDetails) → Map<String,Object>
GET    /channels                          listSlackChannels(AppUserDetails) → List<Map<String,String>>
GET    /projects/{projectId}/channels     getProjectChannels(projectId, AppUserDetails) → List<Map<String,Object>>
POST   /projects/{projectId}/channels     addProjectChannel(projectId, body, AppUserDetails) → Map<String,Object>
DELETE /projects/{projectId}/channels/{channelId}     removeProjectChannel(...) → Void
PUT    /projects/{projectId}/channels/{channelId}/verbosity     updateVerbosity(...) → Void
POST   /test                              sendTest(AppUserDetails, body) → Map<String,Object>
GET    /logs                              getLogs(AppUserDetails, runId?) → List<SlackNotificationLog>
```

#### `api/controller/IntegrationController` (base `/api/integrations`) [`agent.integrations.enabled`]
```
POST   /{source}/webhook                  webhook(source, headers, rawBody) → Map<String,String>
POST   /jira/trigger                      jiraTrigger(body, AppUserDetails) → Map<String,String>
POST   /confluence/trigger                confluenceTrigger(body, AppUserDetails) → Map<String,String>
GET    /runs                              listIntegrationRuns(source?, AppUserDetails) → List<IntegrationRunSummary>
```

#### `api/controller/IntegrationStatusController` (base `/api/integrations`)
```
GET    /status                            status(AppUserDetails) → Map<String,Object>
GET    /slack/status                      slackStatus(AppUserDetails) → Map<String,Object>
```

### Validation Reports / Tests / Analytics / Models

#### `report/ValidationReportController` (base `/api`)
```
GET    /runs/{runId}/report               getReport(runId, AppUserDetails) → ValidationReportDTO
POST   /runs/{runId}/report/share         share(runId, AppUserDetails) → Map<String,String>
GET    /runs/{runId}/report/pdf           pdf(runId, AppUserDetails) → byte[]
GET    /reports/shared/{shareToken}       publicShared(shareToken) → ValidationReportShareDTO
```

#### `testgen/TestGenController` (base `/api`)
```
GET    /runs/{runId}/test-plan            testPlan(runId, AppUserDetails) → Map<String,Object>
GET    /runs/{runId}/test-graph           testGraph(runId, AppUserDetails) → Map<String,Object>
```

#### `testgen/TestResultController` (base `/api`)
```
GET    /runs/{runId}/test-report          report(runId, AppUserDetails) → Map<String,Object>
PUT    /test-results/{id}/mark-invalid    markInvalid(id, AppUserDetails) → Void
POST   /runs/{runId}/run-tests            runTests(runId, AppUserDetails) → Map<String,Object>
POST   /runs/{runId}/apply-test-fixes     applyTestFixes(runId, signature?, AppUserDetails) → 410 Gone
```

#### `analytics/ActivationFunnelController` (base `/api/admin/activation-funnel`) [SUPER_ADMIN]
```
GET    /                                  read(sinceDays=30) → FunnelReport
```

#### `api/controller/AdminAnalyticsController` (base `/api/admin/analytics`)
```
GET    /usage                             usage(entityType, entityId, period=30) → UsageMetrics
GET    /members                           members(orgId, period=30) → List<MemberUsage>
GET    /billing                           billing(entityType, entityId) → BillingOverview
GET    /cost-breakdown                    costBreakdown(orgId, period=30) → Map<String,Object>
```

#### `api/controller/ModelController` (base `/api/models`)
```
GET    /available                         available() → AvailableModelsResponse
```

### V3 anonymous / referral / signup / entitlements / landing / misc

#### `api/EntitlementsController` (base `/api/v3`)
```
GET    /entitlements                      me(AppUserDetails) → EntitlementsResponse
```

#### `api/ReferralController` (base `/api/v3/referrals`)
```
POST   /invite                            invite(InviteRequest, AppUserDetails) → ReferralService.InviteRecord
GET    /state                             state(AppUserDetails) → ReferralService.InviteState
GET    /lookup                            lookup(token) → ReferralService.ReferralLookup  (public)
```

#### `api/SignupClaimController` (base `/api/v3/auth`)
```
POST   /claim-anonymous-session           claim(ClaimRequest, AppUserDetails) → ClaimResponse
```

#### `api/AnonymousRunController` (base `/api/v3`)
```
POST   /anonymous-runs                    submit(AnonymousRunRequest, HttpServletRequest) → AnonymousRunResponse
GET    /anonymous-runs/{sessionToken}     status(sessionToken) → AnonymousRunResponse
```

#### `landing/LandingController` (base `/api/v3`)
```
GET    /landing/stats                     stats() → LandingStats
GET    /landing/sample-runs               sampleRuns() → List<SampleRun>
GET    /public/exec-snapshot              execSnapshot() → ExecSnapshot
```

#### `onboarding/OnboardingController` (base `/api/onboarding`)
```
GET    /sample-specs                      listSamples() → List<SampleSpecDto>
POST   /pick-sample                       pickSample(AppUserDetails, PickSampleRequest) → PickSampleResponse
GET    /sandbox-status                    sandboxStatus(AppUserDetails) → Map<String,Object>
```

#### `export/ProjectExportController` (base `/api/projects/{projectId}/export`)
```
POST   /                                  export(projectId, auth, AppUserDetails) → byte[] (application/gzip)  [SUPER_ADMIN/ORG_ADMIN/BILLING_ADMIN]
```

#### `contact/ContactController` (base `/api/contact`)
```
POST   /                                  submitFeedback(AppUserDetails, ContactRequest) → Map<String,String>
```

#### `rollout/RolloutRunController` (base `/api/v2/runs`)
```
POST   /                                  submit(SubmitRequest) → Map<String,Object> (202)
GET    /{runId}                           poll(runId) → Map<String,Object>
```

### Exception advice (`api/advice/`)
- `GlobalExceptionAdvice` (`@Order(MAX)`): `ErrorResponseException`, `NoResourceFoundException` → 404 canonical, `Throwable` → 500 with 8-char `errorId`.
- `OptimisticLockAdvice`: `ObjectOptimisticLockingFailureException` → 409.
- `ValidationAdvice` (`@Order(HIGHEST_PRECEDENCE)`): `MethodArgumentNotValidException`, `ResponseStatusException`, `AccessDeniedException` → 403, `HttpMessageNotReadableException` → 400, `MethodArgumentTypeMismatchException` → 400, `IllegalArgumentException` → 400, `ConstraintViolationException` → 400, `HttpRequestMethodNotSupportedException` → 405, `MissingServletRequestParameterException` → 400, `BillingException` → 402.

OAuth (handled by `auth/oauth/OAuthSuccessHandler`):
```
GET    /api/auth/oauth2/init?surface=...           302 → provider
GET    /api/auth/oauth2/authorize                  (Spring framework)
GET    /api/auth/oauth2/callback/{provider}        302 → /auth/callback?token=&refresh=
```

---

## 2. Services (orchestration-level)

### Run + pipeline

`integration/RunOrchestrator` — V2 BUILD + PATCH pipeline owner.
```
PipelineRun submitRun(... 7 overloads, canonical 20-arg includes runMode)
boolean cancel(String runId)
boolean pause(String runId)
ResumeOutcome resume(String runId)
CrashResumeOutcome resumeAfterCrash(String runId)
List<String> haltAllActiveRuns(String reason)
Map<String,Object> forceFailTask(String runId, String taskId, String reason)
void retryTask(String runId, String taskId)
void retryValidation(String runId)
void reReview(String runId)
```

`integration/IntegrationManager` — inbound webhook router.
```
String handleWebhook(String sourceType, Map<String,String> headers, String rawBody)
String submitEvent(IntegrationEvent event)
List<String> getEnabledSources()
Optional<InboundAdapter> getInboundAdapter(String sourceType)
List<IntegrationRun> listIntegrationRuns(String source)
```

`integration/scm/ScmPushService` — SCM dispatch (GitHub/Bitbucket).
```
void push(PipelineRun run)
ApplyPushResult pushApplyChanges(PipelineRun run, String commitMessage)
```

`harness/SequentialExecutor` — V2 per-task tier-routed worker dispatcher.
```
ExecutionResult execute(ExecutionRequest req)
ExecutionResult execute(ExecutionRequest req, ResumeRequest resume)
void persistTaskStatus(String runId, String taskIdShort, String status [, String reason [, String diagJson]])
```

`harness/GateStore` — plan/merge approval gate (Redis + DB fallback).
```
void openPlanGate(String runId)
boolean tryClaimPlanGate(String runId, boolean approved)
void setPlanGate(String runId, boolean approved)
boolean awaitPlanGate(String runId)
boolean isPlanGateWaiting(String runId)
void clearPlanGate(String runId)
```

`harness/WorkspaceInitService`
```
void ensureRunWorkspace(String runId)
void cloneFromRepo(String runId, String repoUrl, String pat)
void copyFromRun(String sourceRunId, String targetRunId)
void cloneMultipleRepos(String runId, List<RepoToClone> repos, String pat)
Path runWorkspacePath(String runId)
```

`harness/UiStyleResolver`
```
String loadDesignContent(String designName)
String fuzzyMatchDesign(String hint)
String resolveDesignName(String explicit, String plannerStyle, String projectType)
String extractStyleHint(String userRequest)
String buildFrontendConstraintBlock(String uiStyleJson, String designContent)
String getThemeJson(String themeId)
String resolve(String userRequest, String explicitUiStyle)
String enrichWithDesignName(String uiStyleJson, String designName)
List<String> getCuratedStyles()
Set<String> getAvailableDesigns()
```

`harness/VendoredPathGuard`
```
static boolean isVendored(String workspaceRelativePath)
void augmentGitignore(Path repoDir)
ScrubResult scrubCommittedVendored(Path repoDir)
int purgeStagedVendored(Path repoDir)
```

`state/StateStore` — central V2 state mutator (`@Transactional`). 80+ public methods including `createRun`, `savePlan`, `markApproved/Rejected/Reviewing/Complete/Failed/Partial`, `saveReview/V2`, `savePlanVersion/incrementPlanVersion/recreateTasksFromPlan`, `markApiSpecReady/PreviewReady/Retrying/RetryingWithFeedback`, `lockTask`, `completeTask`, `addTokensToRun`, `setAgentTokens`, `setCostEstimate/UserCostCap/HardCapUsd/CreditsCharged/ActualCost`, `setBatchCostFields/BatchInfo/ApprovedExecutionMode/updateBatchProgress`, `recordGateOpened/Resolved`, `findResumableRun`, `listRuns` (4 visibility-spec overloads), `archiveRun/unarchiveRun/softDeleteRun/restoreRun` (V191), `cancelRun/pauseRun/resumeRun`, `computeMetrics[ForOrg]` (4 variants), `buildTimeline`, `computeModelComparison[ForOrg]`, `computeUserRunsSummary[Global]`, `findRunsByStatuses/getActiveTasksForRun/getWorkingTasks/getOutageQueuedRuns`, `updateLastActivity/markStuckDetected/markRunStuck/markTaskForRetry/blockTask`, `getTaskEventLog/appendTaskLog`, `getTask/failTask/escalateTask/setEscalationLevel/forceCompactionFlag/recordLlmFailure`, `createSubtasks/getSubtasks/completeParentFromSubtasks`, `saveChatMessage/getChatHistory`, `createIterationRun`, `appendSuggestionTasks/incrementApplySuggestionCount`, `findRunsToArchive/Cleanup/TerminalRunsWithoutGcsKey/markGcsArchived`, `static detectTechStack`, `updateHeartbeat`.

`state/InboxService`
```
void send(runId, fromAgent, toAgent, messageType, content)
void sendProviderFallback(runId, agentId, from, to, reason)
void sendFileLockConflict(runId, fromAgent, toAgent, file)
String drainInbox(agentId, runId)
List<AgentInbox> getFallbackEvents(runId) / getDeliveredMessages(runId)
```

### Cost / pricing / credit / entitlements

`cost/RunCostTracker`
```
void record(runId, provider, model, ChatResponse)
void record(runId, taskId, provider, model, ChatResponse)
Snapshot snapshot(runId) / snapshotTask(runId, taskId)
Snapshot finalizeRun(runId)
Estimate estimateRun(provider, model, complexities)
Estimate estimateRunTierAware(provider, complexities [, strategy [, repoCount, projectScoped]])
double computeCost(provider, model, inTok, outTok)
```

`cost/CostHistoryService`
```
void recordOnTerminal(String runId)
Optional<RunCostTracker.Estimate> estimateFromHistory(strategy, provider, repoCount, taskCount)
```

`cost/RunTokenAttributor`
```
void attributePhase(runId, Phase, in, out, provider, model)  // PLANNER | REVIEWER
void attributeWorker(runId, ExecutionResult, totalIn, totalOut, provider, model)
```

`agent/CostEstimator`
```
CostBreakdown estimate(TaskPlan plan [, LlmProvider])
double[] estimateFromTask(TaskDefinition, provider, model)
double[] estimateOverhead(AgentModelConfig)
double computeCost(provider, model, inTok, outTok)
double computeCostForRun(String runId)
```

`entitlements/EntitlementResolver`
```
EntitlementsResponse resolve(String userId)
```

`credit/LifetimeCreditService`
```
int currentBalance(userId) / boolean canSubmit(userId)
int decrementForRun(userId) / int refund(userId) / int grantInviteCredit(userId)
static boolean isPaidTier(AppUser)
```

### Auth / org / users / projects / billing

- `auth/service/AuthService` — register / login / refresh / verify-email / OAuth find-or-create + reset/change password.
- `auth/service/OrgService` — org lifecycle, members, key-source / AI keys / JIRA / Confluence / external-sharing / seats.
- `auth/service/ProjectService` — project CRUD, members, archive/unarchive/soft-delete/restore.
- `auth/service/InvitationService` — org invite create/approve/reject/accept/resend.
- `auth/service/TierPermissionService` — single source for "can user do feature X?". Returns nested `TierPermissions` record.
- `auth/service/TierEnforcementService` — `assertAndIncrementRunCount`, `assertCanSubmitRun`, `assertCanApplySuggestion`, `assertCanCreateProject`, `assertCanLinkRepoToProject`, `assertComplexityAllowed`, `decrementRunCount`, `effectiveOrgLimit/UserLimit`, `resolveUserTier`, `requiresCostCap`, `resolveRunCostBudget`.
- `auth/service/PaymentAuditService` — record + page audit + CSV export.
- `auth/service/ActivityAuditService` — append + hash-chain audit (`record`, `static computeRowHash`, `verifyChain`, `query`).
- `auth/service/MonthlyRunCounterResetJob` — cron `0 0 0 1 * *` Asia/Kolkata + ShedLock.
- `auth/service/WeeklyAdminSummaryJob` — cron `0 0 9 * * MON` Asia/Kolkata.
- `auth/service/GmailOAuthService` — Gmail REST API OAuth.
- `auth/service/UserDataExportService` — `exportForUser(userId) → ExportBundle` (GDPR ZIP).
- `auth/service/MailService` — 23 `@Async` send-* methods (verification, password reset, org invite, project invite, referral invite, escalation, weekly summary, contact, welcome, tier upgrade, suspension, run completion, bonus runs, invoice, dunning, renewal, usage reports).

- `billing/service/BillingService` — plans, status, subscribe, verify, cancel, seats, Razorpay + Stripe webhooks, tier bridge to AppUser/Org. `TIER_RANK={FREE:0,STARTER:1,PRO:2,TEAM:3,ENTERPRISE:4}`.
- `billing/service/InvoiceService` — `generateSubscriptionInvoice`, `generateTopupInvoice`, `generateStripeSubscriptionInvoice`, `generateCreditNote`, `findLatestBySubscriptionId`, `getInvoice`, `getLineItems`, `listByUser/Org/All`, `voidInvoice`, `nextInvoiceNumber()` row-locked.
- `billing/service/InvoicePdfService` — `generatePdf(invoice) → byte[]` (Thymeleaf + iText).
- `billing/service/InvoiceEmailService` — `@Async sendInvoice`.
- `billing/service/RunTopupService` — `getPackages`, `getAvailableBonus[ForOrg]`, `consumeBonusRun[ForOrg]`, `initiatePurchase`, `verifyAndCredit`, `grantBonusRuns`, `listTopups`, `expireOverdueTopups`.
- `billing/service/FounderPricingService` — `@Transactional tryAllocate(userId, orgId, tier) → AllocationResult` (Granted/NoOffer/SoldOut/Inactive), `revokeAllocation`.
- `billing/service/AccountingService` — `recordRevenueEntry`, `recordRefundEntry`, `getRevenueMetrics`, `getGstSummary`, `exportGstr1`, `getTrialBalance`, `getEntries`.
- `billing/service/GstCalculationService` — `calculate(subtotalPaise, buyerStateCode, sellerStateCode, buyerCountry) → GstBreakdown`.
- `billing/service/UsageTrackingService` — `assertWithinLimits`, `increment`, `decrement`, `getCurrentUsage`.
- `billing/service/PlanSeederService` — `@Transactional seedAllPlans`.
- `billing/service/AnalyticsService` — JdbcTemplate-driven `getUsageMetrics`, `getMemberUsage`, `getBillingOverview`, `getCostBreakdown`.
- `billing/service/BillingNotificationService` — `@Async` Gmail-API lifecycle emails.
- `billing/service/RunQuotaRefundListener` — `@Async @EventListener` refunds credits on FAILED / clean-PARTIAL.
- `billing/service/CostOutlierDetector` — `@EventListener` actual > 2× estimate WARN + counter.
- `billing/lifecycle/TierLifecycleService` — `transitionTier`, `transitionPlan`, `listAll`, `executeSunsettingSideEffects`.
- `billing/scheduler/DunningScheduler` / `PeriodEndDowngradeScheduler` / `RenewalReminderScheduler` — daily crons.

- `billing/gateway/PaymentGatewayRouter` — `resolve(entity)`, `resolveByName(name)`, `stripe()`, `razorpay()`, `isStripeEnabled()`, `isRazorpayEnabled()`.
- `billing/gateway/BillingEntityResolver` — `resolve(AppUser) → BillingEntity` (UserBillingEntity or OrgBillingEntity).
- `billing/gateway/StripePaymentGateway` (`implements PaymentGateway`).
- `billing/gateway/RazorpayPaymentGateway` (`implements PaymentGateway` — delegates to `BillingGateway`).
- `billing/gateway/RazorpayGateway` (`implements BillingGateway`).

### Memory / chat / RAG / project workspace

- `memory/RunLedgerAgent` — `generateLedgerEntry(runId)`.
- `memory/MemoryCompactionService` — `compactNow(projectId, orgId)`, `compactIfNeeded(projectId, currentRunNumber, orgId)`.
- `memory/ProjectMemoryProvider` — `buildPlannerContext(projectId)`, `getStats(projectId) → MemoryStats`.
- `memory/ProjectChatService` — `history`, `clearHistory`, `compactHistory`, `compileToSpec`, `send(... callerPat)`.
- `memory/chattools/ProjectChatTools` — `bind(ToolContext) → List<ToolCallback>` exposing 9 tools.

- `chat/ApplyService` — `apply(runId, suggestion, source) → ApplyResult`.
- `chat/ChatService` — `history(runId)`, `send(runId, userMessage) → SendResult`, `renderValidationReport(runId)`.
- `chat/ApplyResultStore` — `put/get/delete` (Redis 1h TTL).

- `project/rag/ProjectDocumentService` — `upload`, `@Async indexAsync(docId)`, `list`, `delete`.
- `project/rag/RetrievalService` — `retrieve(projectId, query [, k, tokenCap]) → String`.
- `project/rag/EmbeddingService` — `embed(List<String>)`, `embedQuery(String)`.
- `project/rag/ChunkingService` — `chunk(text) → List<Chunk>`.
- `project/rag/ChunkStore` — `insertAll`, `nearest(projectId, queryEmbedding, k)`, `deleteByDocument`.
- `project/rag/DocumentExtractor` — `extract(file, mimeType) → String` (Tika).

- `project/workspace/ProjectWorkspaceService` — `rootFor`, `resolve`, `initIfMissing`, `writeAndCommit`, `readFile`, `writeRawFile`, `delete`.
- `project/workspace/ProjectWorkspaceWarmer` — `@Async warmDiskCache(projectId)`.

### Project architecture / techdebt / scoring / templates / PRs

- `codeintel/ProjectArchitectureService` — `generate`, `regenerateForRepo`, `latestRepoArch`, `latest`, `versions`, `annotate`, `latestCodeMap`.
- `codeintel/CodeMapGenerator` — `generateWithSha`, `generate` (2 overloads).
- `codeintel/JavaAstSymbolExtractor` — `static extract(content)`.
- `codeintel/FilePurposeSummaryService` — `summariseFile`, `generateAndPersist`.
- `codeintel/RepoArchitectureGenerator` — `generate(repoFullName, codeMap, provider)`.
- `codeintel/ArchitectureDocGenerator` — `generate(projectId, repoArchDocs, repoNames, provider)`, `static deterministicCombined`.
- `codeintel/ProjectBriefService` — `buildBrief`, `summarise`.
- `codeintel/ProjectDocGeneratorService` — `generate(projectId, docType, generatedByUser, provider)`, `latest`.
- `codeintel/VersionRetentionService` — 4 `pruneXxx` methods.
- `codeintel/StalenessService` — `report`, `invalidate`.
- `codeintel/ArchitectureDiffService` — `diff(projectId, fromVersion, toVersion, callerUserId)`.
- `codeintel/RunCodeMapService` — `isEligible`, `generateForRun`, `findByRunId`.

- `project/service/ProjectRepoService` — repo lifecycle + linking (see Section 1 endpoints).
- `project/service/WorkspaceHealthService` — `report(projectId, callerUserId)`.
- `project/service/StackDetector` — `detect(fullName, pat)`.
- `project/service/StackRefreshJob` — daily cron `0 0 3 * * *`.

- `project/techdebt/TechDebtService` — `build(projectId, sinceDays) → Report`.
- `project/techdebt/TechDebtFixService` — `dispatchFixForCategory(projectId, category, user)`.

- `project/estimation/EstimationService` — `estimate(projectId, requirement) → Estimate`.
- `project/template/SpecTemplateService` — `listForProject`, `createForProject`, `createForOrg`, `delete`.
- `project/security/ProjectAccessGuard` — `assertCanRead`, `assertCanWrite`.

- `project/pr/PullRequestRecordService` — `recordCreated` (V82 + V169 overloads), `applyWebhookUpdate`, `listForRun`, `static extractPrNumber`.
- `project/pr/PrRefreshService` — `refreshProject(projectId [, callerPat [, forceAll]])`.
- `project/pr/PostMergeCascadeService` — `onMerged`, `@Async regenerateAfterMerge`, `resolveProjectId`, `readPostMergeBars`, `dismissPostMergeBars`.
- `project/pr/CiFailureAutoFixService` — `@Transactional onCheckRunFailed(repoFullName, headBranch, summary, url)`.

### Code review / PR review / inbox

- `prreview/PrReviewService` — `assertTierAndQuota`, `submitReview`, `findById`, `findByPr`, `findByProject`, `commentsFor`.
- `prreview/PrReviewAutofixService` — `dispatch(reviewId, user)`.
- `prreview/inbox/PrCommentInboxService` — `ingest`, `listForRun`, `countPending`, `findForRun`, `dismiss`, `markAddressed`.
- `codereview/CodeReviewService` — `assertTierAndQuota`, `submitScan`, `findScan`, `listForProject`, `latestForProjectRepo[AnyStatus]`, `issuesFor`, `openIssuesFor`.
- `codereview/SemgrepRunner` — `scan(workspaceRoot) → ScanResult`.
- `codereview/CodeReviewAutofixService` — `dispatchFixForSeverity(scanId, severity, user)`.

### Contracts / standards / ADRs / invariants / activity

- `contracts/detection/ContractDetectionService` — `runForRepo(repoId, projectId, fileLister, contentLoader)`.
- `contracts/breaking/BreakingChangeService` — `classify(type, oldContent, newContent) → ContractDiff`.
- `contracts/drift/DriftDetectionService` — `checkContract`, `checkAllForRepo`, `checkAllForProject`.
- `contracts/injection/ContractInjectionService` — `compose(projectId)`.
- `contracts/gate/PlanBreakingChangeReport` — `compose(projectId, IntendedChange[])`.

- `standards/EffectiveStandardsResolver` — `resolve(orgId, projectId, projectYaml)`.
- `standards/StandardsPromptComposer` — `compose(List<Effective>)`.
- `standards/StandardsRunner` — `run(effective, repoRoot, repoFilePaths, sourceFiles)`.
- `standards/model/StandardsParser` — `parse(yamlBody) → List<Standard>`.
- `standards/override/OverridePatternDetector` — `detect(projectId, standardIds)`.
- `standards/enforcement/lint/LintProcessRunner` — `run(workingDir, argv, timeoutSeconds)`.
- `standards/enforcement/llmreview/LlmReviewEnforcer` — `composePromptBlock`, `parseReviewResult`.
- `standards/enforcement/ast/AstCheckEnforcer` — `enforce(spec, files [, FileReader])`.
- `standards/enforcement/filepattern/FilePatternEnforcer` — `enforce(spec, repoFilePaths)`.

- `arch/invariants/InvariantChecker` — `check(invariantYaml, repoFilePaths, contentLoader)`.
- `adr/AdrInjectionService` — `compose(projectId)`.
- `adr/AdrFrontmatterParser` — `parse(fileContent) → Parsed`.

- `activity/ActivityEventEmitter` — `@Async emit(projectId, type, actorUserId, targetType, targetId, summary, payload)`.
- `activity/ActivityFeedPartitionJob` — cron `0 30 2 * * *`.
- `activity/ActivityFeedRetentionJob` — cron `0 0 3 * * *`.

### Execution View

- `execution/service/ExecutionOrchestrator` — `createExecution`, `@Async triggerAnalysis`, `approveArchDoc`, `kickoffPlanning`, `@Async dispatchUnified`, `@Async pollAndMirrorV2`, `@Async approvePlanAndExecute`, `@Async executeApprovedPlan`, `abortExecution`, `buildReport`.
- `execution/service/ExecutionPlannerService` — `@Async generatePlan(runId)`.
- `execution/service/SolutionExecutionService` — `executeSolution`, `handleFailure`, `processDecision`, `signalManualFixDone`.
- `execution/service/SolutionDependencyResolver` — `resolveExecutionOrder`, `getBlockedDownstream`.
- `execution/service/CrossRepoContextService` — `recordContracts`, `buildHandoverContext`.
- `execution/service/IncrementalCodeMapService` — `refresh(repoFullName, pat)`.
- `execution/service/ExecutionBranchService` — `branchName`, `tagStart/End`, `tagSolutionStart/End`, `createFinalPr`.
- `execution/service/ExecutionJiraSync` — `createTicket`, `updateTicket`.
- `execution/service/ExecutionCostAggregator` — `compute(runId)`, `recomputeAndPersist(runId)`.
- `execution/service/AdminReconciliationService` — `@Async @EventListener onApplicationReady`, `@Scheduled scheduledReconcile`, `reconcileOrphans`.
- `execution/service/ExecutionRunCasUpdater` — `boolean casUpdate(runId, Consumer<ExecutionRun>)`.
- `execution/service/ExecutionTotalsBackfillJob` — `@PostConstruct @Transactional backfill()`.

### Migrations / readymades / sharing / referral / signup / onboarding

- `migration/service/MigrationService` — `list`, `detail`, `static isLocked`, `requiredTierFor`.
- `migration/service/MigrationDryRunService` — `dryRun(slug, projectId, repoIds)`.
- `migration/service/MigrationStackMatcher` — `static match(...)`.
- `migration/service/MigrationOrchestrator` — `apply(slug, ApplyRequest, callerUserId)`.
- `migration/seeder/MigrationRecipeSeeder` — `@PostConstruct onStartup`, `@Transactional reseed`.

- `readymade/service/ReadymadeService` — `list`, `detail`, `categoryCounts`, `macroGroupCounts`.
- `readymade/service/VariantResolver` — `resolve(solutionId, projectId, repoId)`.
- `readymade/service/ReadymadeOrchestrator` — `instantiate(slug, InstantiateRequest, callerUserId)`.
- `readymade/service/StackNormalizer` — `static normalizeLanguage/normalizeFramework`.
- `readymade/seeder/ReadymadeSeeder` — `@PostConstruct onStartup`, `@Transactional reseed`.

- `sharing/service/ProjectInviteService` — `createInvite`, `accept`, `revoke`, `listPending`, `findByToken`.
- `sharing/service/ProjectShareLinkService` — `createLink`, `revoke`, `resolveActive`, `recordView`, `listForProject`, `activeCountForProject`.

- `anonrun/AnonymousRunService` — `submit(spec, clientIp) → Outcome`, `findSession`, `bindToUser`.
- `anonrun/AnonymousRunSessionStore` — `save`, `find`, `bindToUser` (Redis 24h TTL).
- `anonrun/AnonymousRunRateLimiter` — `tryConsume(ip)` (3/hour).
- `anonrun/AbuseHeuristic` — `check(spec) → Decision`.
- `anonrun/AnonymousRunDispatcher` (interface) + `StubAnonymousRunDispatcher`.

- `referral/ReferralService` — `recordInvite`, `bindReferral`, `onRefereeFirstReadyRun`, `getInviteState`, `lookupByToken`.
- `referral/ReferralCompletionHook` — `onTerminalStatus(runId, status, userId)`.

- `onboarding/SandboxProvisioningService` — `provisionIfNeeded(userId, sampleId)`, `currentSandboxId(userId)`.
- `onboarding/SampleSpecRegistry` — `listAll`, `findById`.
- `onboarding/SandboxCleanupJob` — cron `0 5 * * * *`.

### Validators / reports / tests / smoketest

- `validator/CodeValidator` (interface) / `CodeValidatorImpl` — `validateFile`, `validateProject`.
- `validator/gate/GateChain` — `execute(toolchain, projectRoot, filePath, level, perGateTimeout)`.
- `validator/execution/ProcessExecutor` — `execute(ProcessCommand) → ProcessResult`. Allowlists git/mvn/npm/npx/node/mypy/ruff/eslint/tsc/javac/python3/pytest/docker/etc.

- `report/ValidationReportService` — `generateAndPersist`, `getByRunId`, `findEntityByRunId`, `getByShareToken`, `share(runId)`, `static extractFailureSignature`, `static testFactorFromCoverageLevel`, `static rubricTestPointsFromCoverageLevel`, `static mapVerdictToV2Name`, `static isAccuracyHallucinationDescription`.
- `report/ValidationReportGenerator` — `generate(ReportInput) → LlmReportResult`.
- `report/ValidationReportPdfService` — `generatePdf(report) → byte[]`.
- `report/VerifiedScoreBackfillService` — `@Transactional backfillAll() → BackfillResult`.
- `report/UsageReportService` — `triggerReports(period) → int`.

- `testgen/TestGenOrchestrator` — `runTestGenPipeline(runId, userRequest, planJson)`.
- `testgen/TestPlannerAgent` — `planTests(...)`.
- `testgen/TestWorkerAgent` — `generateTestClass(...)` + retry overload.
- `testgen/TestCompilationGate` — `compileTests(runId)`, `markFailedTasks`.
- `testgen/TestExecutionService` — `executeTests(runId, testPlanId, previewBaseUrl)`.
- `testgen/TestPreviewCoordinator` — `onPreviewReady`, `@EventListener onTestsReady`, `cancelIfPending`, `triggerManualExecution`.
- `testgen/HeadlessTestExecutor` — `execute(runId, testPlanId)`.
- `testgen/TestReportGenerator` — `generateAndEmit(runId, planId, summary, previewUrl)`.
- `testgen/TestProjectScaffold` — `scaffold(runId, isStaticHtml)`, `getTestSourceDir`.
- `testgen/ApiProber` — `probe(baseUrl, knownPaths)`.
- `testgen/ApiSurfaceExtractor` — `extractPaths`, `extract`.
- `testgen/ApiConventionsGenerator` — `generate(userRequest, planJson, isCli, isStaticHtml)`.
- `testgen/SurefireReportParser` — `parse(reportsDir, testPlanId)`.
- `scoring/TestCoverageRescorer` — `@EventListener onTestExecutionCompleted`.
- `scoring/RunRubricVerifier` — `@Transactional verifyRubric(runId)`.
- `scoring/ReviewScoringService` — `score(run, review, accuracy, verifiedScore, figmaUrl) → Scores`.

- `smoketest/CliSmokeRunner` — `run(workspaceRoot) → Result`.
- `smoketest/BuildCommandResolver` — `resolve(workspaceRoot, plannerCommand)`, `static normalizePython`.

### Repo / SCM / integrations / Slack

- `repo/GitHubRepoService` — `createRepo`, `closePullRequest`, `deleteBranch`, `installWebhook`, `compareCommits`, `updateRepoDescription`, `listRepoFiles`, `readRepoFile`, `cloneRepo`, `generateRepoName/Slug`, `getDefaultBranch`, `getRepoSizeKb`, `getBranchHeadSha`, `createPullRequest`, `updatePullRequestBody`, `requestReviewers`, `fetchPrReviewComments`, `getPullRequestSnapshot`, `getCheckRunRollup`, `listOwnerRepos`, `static extractOwnerRepo`.
- `repo/BitbucketRepoService` — `cloneRepo`, `getDefaultBranch`, `createPullRequest`, `validateCredentials`.
- `repo/MonorepoDetector` — `detect(repoFilePaths) → Verdict`.

- `integration/IntegrationConfig` — bean wiring for JIRA/Confluence/ServiceNow/PagerDuty/Bitbucket/Slack.
- `integration/IntegrationOutboundListener` — `@EventListener` for RunStarted/MergeCompleted/RunFailed.
- `integration/PerkyMessenger` — periodic LOG SSE events.
- `integration/IntegrationEventLogCleanupJob` — daily 3am prune ≥7d.
- `integration/context/ReferenceExtractorService` — `extractAndEnrich`, `extractFromClarification`.
- `integration/jira/JiraAdapter` + `JiraClient` — fetch/comment/transition.
- `integration/confluence/ConfluenceAdapter` + `ConfluenceClient`.
- `integration/pagerduty/PagerDutyAdapter` + `PagerDutyClient`.
- `integration/servicenow/ServiceNowAdapter` + `ServiceNowClient`.
- `integration/bitbucket/BitbucketAdapter` + `BitbucketClient`.
- `integration/slack/SlackNotificationService` — `@EventListener onRunEvent`, `sendTestMessage`.
- `integration/slack/SlackRateLimiter` — `acquire`, `tryAcquire`.
- `integration/slack/SlackMessageBuilder` — `build(eventType, run, payload)`.
- `integration/slack/SlackOAuthService` — `generateInstallUrl`, `handleCallback`, `disconnect`, `isConnected`.
- `integration/slack/SlackClient` — `chatPostMessage`, `oauthV2Access`, `authTest`, `conversationsList`, `authRevoke`.
- `integration/slack/SlackNotificationLogCleanupJob` — daily 4am prune ≥90d.

### Notifications / SSE / compliance / governance / saga / recovery / observability / lint / security

- `notification/InAppNotificationService` — `create`, `getNotifications`, `getUnreadCount`, `markRead`, `markAllRead`.
- `notification/RunCompletionNotifier` — `@Async @EventListener` for RunCompleted/RunFailed/GateOpened/QuotaWarning.
- `notification/RunCompletionEmailListener` — `@Async @EventListener` PR-O run-completion email.
- `notification/pref/NotificationPrefService` — `shouldNotify`, `listOverrides`, `setPreference`.

- `sse/SseBroadcaster` — `subscribe(runId [, lastEventId])`, `emit(runId, RunEvent)`, `emitDirect`, `complete(runId)`.
- `sse/RunDetailSseService` — `@Async @EventListener onRunEvent` pushes full RunDetail.

- `compliance/DataRetentionService` — cron `0 0 3 * * *` retention sweep; `@Transactional eraseUserData(userId)`.
- `compliance/WorkspaceDiskCleanupJob` — cron `0 30 3 * * *` per-run dir cleanup.
- `compliance/StaleScmCleanupJob` — cron `0 45 3 * * *` close-stale-PRs + delete-stale-branches.

- `governance/GovernanceGuard` — `assertCanApproveCost`, `assertProjectSpendCap`, `assertRepoOwnerAllowed`, `static ownerAllowedBy`.

- `saga/SideEffectOutbox` — `enqueue(runId, effectType, payload)`, `enqueueCompensation`.
- `saga/SideEffectExecutor` — `@Scheduled(fixedRate=30000) processDue()`, `processEntry(entry)`.

- `recovery/StartupRunResumer` — `@Async @EventListener(ApplicationReadyEvent) onApplicationReady`, `@Transactional scanAndRecover`.

- `observability/Metrics` — central `executespec.v2.*` Micrometer facade. Public methods: `recordRunOutcome`, `recordRunLatency`, `recordPlannerLatency`, `recordPlannerTaskCount`, `recordTaskStatus`, `recordTaskLatency`, `recordSmokeTest`, `recordWorkerIterations`, `recordWorkerOutcome`, `recordToolCall`, `recordLlmLatency`, `recordKeySource`, `recordClassifierVerifier`, `recordReviewerVerdict`, `recordContextSave`.

- `lint/LintPass` — `run(workspace) → LintReport`.

- `security/InputSanitizer` — `scan(userRequest [, GuardrailLevel])`.
- `security/CodeAbuseScanner` — `scan(filePath, content [, GuardrailLevel]) → ScanResult`.
- `security/KeyEncryptor` — `encrypt`, `decrypt` (AES-256-GCM via JWT-derived key).
- `security/ContextSanitizer` — `sanitizeInput`, `wrapInBoundary`, `sanitizeOutput`.
- `security/dependency/DependencySecurityPolicy` — `validateSegment(seg, fullCommand [, level])`.
  (`SupplyChainScanner` interface + `NoopSupplyChainScanner` deleted 2026-05-26, RL-2026-05-041 — zero callers.)
- `security/guardrail/GuardrailLevel` (enum LOW/MEDIUM/HIGH) + `GuardrailLevelResolver.current()`, `recordBlock(layer)`.

### Crypto / contact / export / finding / run helpers / SWE-bench / ProgramBench / batch / rollout / eval / preview / deploy / folder

- `crypto/SimpleKeyEncryptionService` — `encrypt`, `decrypt`, `isEncryptionActive`, `isLikelyEncrypted`.
- `crypto/KeyMigrationRunner` — `@EventListener(ApplicationReadyEvent) migrateExistingKeys()`.
- `crypto/EncryptedValueConverter` (`@Converter implements AttributeConverter<String,String>`).

- `contact/ContactService` — `submitFeedback(email, name, subject, body)`.
- `export/ProjectExportService` — `composeBundle(projectId) → byte[]` (tar.gz).
- `finding/FindingPatchSubmitter` — `submit(Finding) → SubmitResult`.

- `run/RunSkipPushFlag` — `mark`, `consume`.
- `run/sync/RunSyncService` — `syncToOriginalCommit(runId)`, `sync(runId, branchOverride)`.

- `swebench/SwebenchTaskService` — `@EventListener onRunStarted`, `submit(instanceId, provider, model)`, `@Async captureOnTerminalAsync`.
- `swebench/SwebenchInstanceLoader` — `findById`, `allInstanceIds`, `imageNameFor`.
- `swebench/SwebenchContainerStore` — `put/get/remove`.
- `swebench/SwebenchBootstrap` — `@EventListener bootstrapOnStartup`.

- `programbench/ProgramBenchTaskService` — `submit(taskId, provider, model)`, `@EventListener onRunStarted`, `@Async captureOnTerminalAsync`.
- `programbench/ProgramBenchContainerStore` — `put/get/remove`.
- `programbench/ProgramBenchBootstrap` — `@EventListener bootstrapOnStartup`.

- `batch/BatchCoordinator` — `enabled()`, `submit(runId)` (placeholder).
- `batch/BatchClientRouter` — `forProvider`, `hasClient`.
- `batch/BatchPollService` — `@Scheduled pollInFlightJobs()`.
- `batch/BatchResumeService` — `resume` (placeholder).

- `rollout/RunService` — `find(runId)`, `submit(Request) → Run`.
- `rollout/RunRegistry` — `put`, `get`, `size`.

- `eval/ModelEvaluator` — `evaluate(providerFilter) → EvalReport`.
- `eval/EvalScorer` — `scoreAccuracy`, `scoreSpeed`, `normalizeCostScores`, `overallScore`, `countMatchedPatterns`, `isWithinLengthLimit`, `lengthPenalty`, `static latencyCeilingMs`.
- `eval/EvalTestCaseRegistry` — `allTestCases`, `testCasesForTier(tier)`.
- `eval/LlmJudgeValidator` — `judge(testCase, modelResponse)`.
- `eval/PlannerScaffoldValidator` — `static validate(plan, testCase)`.

- `deployment/DeploymentService` — `deploy`, `rollback`, `deleteDeployment`, `cleanupDeployment`, `@PostConstruct validatePrerequisites`, `isEnabled`.
- `deployment/DockerfileGenerator` — `detectAndGenerate(workspacePath) → DeploymentStrategy`.
- `deployment/ArtifactRegistryClient` — `buildImage`, `pushImage`, `fullImageUri`.
- `deployment/CloudRunClient` — `deploy`, `deployPreview` (+overload), `listPreviewServices[WithLabels]`, `rollbackToRevision`, `deleteService`, `deleteImage`, `healthCheck`.
- `deployment/DeploymentGitHubService` — `createAndPush`, `pushToExisting`, `skipGitHub`.
- `deployment/DeploymentCleanupJob` — `@Scheduled cleanupExpired`.
- `deployment/WorkspaceLockService` — `getLock`, `removeLock`.
- `deployment/GcsWorkspaceStore` — `uploadZip`, `downloadToTemp`, `signedDownloadUrl`, `exists`, `delete`, `objectKey`.
- `deployment/WorkspaceExportService` — `exportWorkspace`, `exportAsRepoZip`, `static isSecretFile`.
- `deployment/WorkspaceRestoreService` — `ensureWorkspaceAvailable(runId)`.
- `deployment/WorkspaceArchiveJob` — cron `0 0 * * * *`.
- `deployment/WorkspaceGcsUploadListener` — `@Async @EventListener` + fallback sweep.
- `deployment/WorkspaceFileService` — `listFiles`, `readFile`, `readFileDiff`, `listFilesWithStatus`.
- `deployment/PreviewAgent` — `run(runId, allTasks) → WorkerReport`.

- `deployment/credential/CredentialService` — `getRequiredPermissions`, `storeCredential`, `validateCredential`, `getCredentialSummary`, `listCredentials`, `deleteCredential`.
- `deployment/credential/UserGcpCredentialProvider` — `getCredentials`, `getCredentialsOrThrow`, `getGcpProjectId`, `hasCredentials`.
- `deployment/credential/CredentialEncryptionService` — `encrypt`, `decrypt`.

- `deployment/preview/PreviewService` — `analyze`, `start`, `stop`, `rebuild`, `getStatus`, `getLogs`, `execInContainer`, `@Scheduled cleanupExpired/Orphans`, `@PreDestroy shutdownAll`.
- `deployment/preview/PreviewPortPool` — `allocate`, `release`, `activeCount`, `getPort`, `findPortByPrefix`.
- `deployment/preview/GlobalPreviewLimiter` — `tryAcquire`, `release`.
- `deployment/preview/CaddyProxyManager` — `addRoute`, `removeRoute`, `buildUrl`.
- `deployment/preview/ComposeFileGenerator` — `generate(workspace, type, mode, db, envVars, hostPort)`.
- `deployment/preview/ComposeExecutor` — `up`, `down`, `logs`, `isRunning`, `waitForHealthy`, `listProjects`, `isAvailable`, `execInService`.
- `deployment/preview/DockerExecutor` — `isDockerAvailable`, `build`, `run`, `stop`, `logs`, `isRunning`, `listPreviewContainers`, `inspectHostPort`.
- `deployment/preview/DatabaseDetector` — `detect(workspacePath) → DatabaseType`.
- `deployment/preview/EnvVarDetector` — `detect(workspace) → List<DetectedEnvVar>`.
- `deployment/preview/DevDockerfileGenerator` — `detect`, `generate(type, ws [, mode])`, `generateForCloudRun`, `dockerIgnore`, `detectMode`.
- `deployment/preview/PreviewProxyFilter` — servlet filter for `preview-{id}.*`.

- `deployment/preview/vm/PreviewDeploymentService` — `deploy`, `teardown`, `extend`, `hasCapacity`.
- `deployment/preview/vm/PreviewGarbageCollector` — `@Scheduled(fixedRate=600s) collectExpired`.

- `deployment/preview/engine/PreviewEngineHandoff` — `start`, `stop`.
- `deployment/preview/engine/PreviewEngineClient` — `registerFromImage`, `unregister`, `isEnabled`, `registryUrl`.
- `deployment/preview/engine/PreviewImagePusher` — `buildAndPush`.
- `deployment/preview/engine/BuildCachePruneJob` — `@Scheduled prune`.

- `deployment/engine/DeployEngineOrchestrator` — `deployCloudRunDirect`, `rollback`, `destroy`.
- `deployment/engine/UserCloudRunDeployer` — `deploy`, `deleteService`, `rollbackToRevision`, `healthCheck`.
- `deployment/engine/UserImageBuilder` — `build`, `ensureArtifactRegistryRepo`, `ensureBuildBucket`.

- `deployment/analyzer/DeploymentAnalyzer` — `analyze(workspace) → InfrastructureManifest`.
- `deployment/analyzer/DeploymentDecisionEngine` — `decide(manifest) → DeploymentDecision`.
- `deployment/analyzer/StorageDetector` / `CacheDetector` / `QueueDetector` / `HealthCheckDetector` / `StatelessnessDetector` — each `detect(...)`.

### Folder / activity / scoring (continued) / config

- `folder/ExecutespecFolderWriter` — `writeFiles(repo, projectSlug, files, prTitle, prDescription, actorPat)`, `readFile`, `listFiles`, `hasDrift`.
- `folder/FolderReadmeGenerator` — `render(repo, projectSlug)`.
- `folder/BootstrapService` — `@Async bootstrapAsync`, `static slugify`.
- `folder/ProjectConfigPathResolver` — `resolve(projectId, repoId) → Resolution`, `resolveConfigFilePath`.

### Tools (legacy facades)

- `tool/FileSystemTool` (V1 Spring-AI `@Tool` facade).
- `tool/GitTool` (V1 Spring-AI `@Tool` facade for worktrees + multi-repo push).
- `tool/FileLockTool` — DB-backed per-file lock (dormant in V2).
- `tool/MavenDependencyValidator` — `validate(workspaceRoot)`, `formatError(result)`.
- `tool/WorkspaceContext` — `static set/getWorkDir/setTaskId/getTaskId/clear`.

### Tenant

- `tenant/TenantContext` — `static setTenantId/getTenantId/clear`.
- `tenant/TenantFilter` — `Filter` (Order 1) X-Tenant-Id header validation.
- `tenant/TenantInterceptor` — `@PrePersist` JPA listener.
- `tenant/TenantContextThreadLocalAccessor` — Micrometer `ThreadLocalAccessor<String>`.

### Helpers

- `util/SafeYamlLoader` — `static safe()`, `static load(yamlBody|InputStream|Reader)`.
- `util/PoolSizing` — `static int coreMultiple(multiplier, min, max)`.
- `util/HmacUtil` — `static String hexSha256(secret, body)`.
- `composer/SpecComposer` — `static compose(template, userValues, defaults)` + overloads; `defaultsFrom`.
- `composer/SlotValidator` — `static validate`, `validateAll`.
- `assessment/FeatureAssessmentService` — `assess(projectId, userRequest)`, `assessAsJson`.
- `apitests/ApiTestSuiteAgent` — `generateAsync(PipelineRun)`.
- `apitests/ApiTestSuiteProperties` — `isEnabled`, `getModel`, `getProvider`.
- `optimiser/PromptOptimiserAgent` — `runIfDue(PipelineRun completedRun)`.
- `optimiser/PromptAuditService` — `record`, `recordRunOutcome`, `getHistory`.
- `optimiser/PromptPatternAnalyser` — `analyse() → PromptPatterns`.

### Rate limiting

- `ratelimit/RateLimitFilter` — `OncePerRequestFilter` over `/api/**`.
- `ratelimit/RateLimitPolicy` — `getLimit(group, tier [, level])`, `classifyGroup(method, path)`, `getAuthSubType(path)`.

### Analytics

- `analytics/EventTrackingService` — `@Async track(userId, EventName, properties)`, `isFirstTime`.
- `analytics/PostHogClient` — `capture`, `identify`, `isEnabled`.

### Provider routing

- `router/ModelRegistry` — `canonicalize`, `providerFor`, `isKnownModel`, `contextLimitFor[OrPrefixFallback]`, `maxOutputForModel`, `pricingFor`, `notesFor`, `displayLabelFor`, `fallbackChainFor[Tier]`, `freeTierModel/Provider`, `workhouseModelFor`, `planningModelFor`, `lightweightModelFor`, `allModels`.
- `router/ModelResolver` — `resolve(agentName, provider [, tierOverride])`, `resolveFallbackExcluding(agent, provider, exclude)`.
- `router/ProviderHealthTracker` — `isHealthy`, `recordSuccess`, `recordRateLimit`, `recordFailure`.
- `router/ProviderStatusService` — `isAnyProviderHealthy`, `recordQueuedDueToOutage`, `recordOutageQueueReleased`, `@Scheduled pollStatusPages`.
- `router/ProviderRecommendationService` — `recommend(userId, projectType, runtimeSurface)`, `reasonCode`.
- `router/AvailableProviderResolver` — `resolve(runId) → Set<LlmProvider>`.
- `router/ModelPerformanceTracker` — `recordCall`, `getScore`, `getAvgLatencyMs`, `isMateriallyBetter`.
- `router/RunProviderPinStore` — `pinIfAbsent`, `getPin`, `clearPin`.

### LLM dispatch / BYOK

- `agent/LlmRouter` — V2 native tool-calling. `ChatResponse call(LlmRequest request)`.
- `agent/FallbackLlmRouter` — legacy V1 fallback router. `callWithFallbackTracked(provider, model, prompt, runId, agentId, FallbackMode, iteration, justCompacted)`, `callWithFallback`, `callDirect`, `clearRunCallCounter`, `getRunCallCount`, `setRunUserTier`, `getRunUserTier`.
- `agent/OpenAiNativeToolClient` — `ChatResponse call(OpenAiToolCallRequest)`.
- `agent/GeminiNativeToolClient` — `ChatResponse call(GeminiToolCallRequest)`.
- `agent/ApiKeyResolver` — `resolve(userId) → ResolvedKeys`, `isOwnKeysMode`, `validateKeyAvailability`, `resolveOrgIdForUser`.
- `agent/RunKeyStore` — L1 Caffeine + L2 Redis encrypted. `put`, `get`, `remove`.
- `agent/RunKeyContextStore` — `resolveAndPut(runId, userId)`, `get(runId)`, `evict(runId)`.
- `agent/PromptAssembler` — `assemble(path [, variables])`, `assembleAndTrack`, `loadFile/loadFileSafe`.
- `agent/PromptResourceVerifier` — `@PostConstruct verify()`.
- `agent/SpecClassifier` (static) — `classify(spec) → Classification`.
- `agent/PlannerOutputUtils` (static) — `parsePlanFromArgs`, `validatePlan`, `warnIfConstraintsUnderpropagated`, `salvagePlanFromText`, `truncate`, `cleanTitle`, `resolveProviderForTitle`.
- `agent/TokenBudgetPolicy` — `evaluate(used, soft, hard) → BudgetAction`.
- `agent/TokenBudgetMonitor` — `check`, `evaluateBudget`, `resolveEffectiveComplexity`, `maxOutputTokens`, `maxOutputForModel`, `contextLimitFor`.
- `agent/PlanStrategyCircuitBreaker` — `degradeIfOpen`, `recordSuccess`, `recordFailure`.

---

## 3. Agents

Every `*Agent.java` and related agent-tier services. Tier from `application.yml model-matrix.agent-tier-map`. Default provider is **anthropic** (1.0.0-M1 change).

| Agent class                                                | Tier        | Purpose                                                                                                     |
| ---------------------------------------------------------- | ----------- | ----------------------------------------------------------------------------------------------------------- |
| `agent/SmartClassifierAgent`                               | LIGHTWEIGHT | Phase-0: heuristic-fast-path then LLM-verified `PlanStrategy` (SINGLE_SHOT/SMALL/MEDIUM/LARGE/LEGACY); 1h Redis cache. |
| `agent/OutlinePlannerAgent`                                | PLANNING    | Phase-1: emits Plan via `submitOutline`; FULL_PLAN or SKELETON_ONLY. Also `synthesizeSingleShot`.            |
| `agent/TaskExpanderAgent`                                  | WORKHORSE   | Phase-2: fills body of one skeleton task via `submitTaskDetail`; parallel over LEAF skeletons.              |
| `agent/CoherencePlannerAgent`                              | LIGHTWEIGHT | Phase-3: zero-or-more `patchPlan` minimal-delta tool calls; non-fatal.                                      |
| `engine/WorkerAgent`                                       | WORKHORSE   | Sequential multi-turn worker; native tool calls; one progress signal + Lever B salvage; stagnation guard. (Relocated `agent/`→`engine/`, Phase 4.) |
| `agent/PatchAgent`                                         | WORKHORSE   | PATCH-mode entry — single thin loop for SWE-bench / autofix surfaces; per-provider iter caps (150/100/120). |
| `reviewer/ReviewerAgent`                                   | PLANNING    | General + wiring pass via WorkerAgent; PostEditFormatter + final build; verdict CLEAN/FIXED_ISSUES/UNRESOLVED_ISSUES/REVIEW_FAILED. |
| `reviewer/AccuracyAssessmentService`                       | PLANNING    | Spec-adherence LLM auditor (4 dims); Layer 3 grep verify + Layer 4 execution-time filter.                    |
| `reviewer/PostEditFormatter`                               | n/a         | Deterministic non-LLM formatter pass (black/prettier/gofmt).                                                |
| `agent/ConversationAgent`                                  | LIGHTWEIGHT | Post-run chat + apply-suggestion plan (≤6 tasks); `ApplySuggestionTooLargeException`.                       |
| `agent/RunTitleSummarizer`                                 | LIGHTWEIGHT | 5-9 word imperative title from spec.                                                                        |
| `codeintel/RepoArchitectureGenerator`                      | LIGHTWEIGHT | Per-repo HLD+LLD markdown from codemap.                                                                     |
| `codeintel/ArchitectureDocGenerator`                       | PLANNING    | Combined project-wide architecture markdown synthesis.                                                      |
| `codeintel/ProjectDocGeneratorService`                     | WORKHORSE   | EXPLANATION + SETUP markdown docs; V195 PR-Q cache.                                                         |
| `codeintel/ProjectBriefService`                            | LIGHTWEIGHT | 2-4 sentence project brief + heading top-3.                                                                 |
| `codeintel/FilePurposeSummaryService`                      | LIGHTWEIGHT | PR-N2 2-3 sentence per-file summaries.                                                                      |
| `memory/RunLedgerAgent`                                    | LIGHTWEIGHT | Per-completed-run digest (≤2000 chars).                                                                     |
| `memory/MemoryCompactionService`                           | LIGHTWEIGHT | Rewrite `project_memory_snapshot` every 10 runs.                                                            |
| `memory/ProjectChatService` (`ProjectChatAgent` tier)      | PLANNING    | Project-scoped chat copilot with 9-tool callback set.                                                       |
| `prreview/PrReviewAgent`                                   | PLANNING    | Single `submitReview` tool call against PR diff.                                                            |
| `codereview/CodeReviewAgent`                               | PLANNING    | Triages static findings + adds architectural/logic findings via `submitCodeReview`.                         |
| `report/ValidationReportGenerator`                         | WORKHORSE   | LLM markdown narrative for validation report.                                                               |
| `testgen/TestPlannerAgent`                                 | WORKHORSE   | Test-plan JSON (≤6 classes × ≤8 methods).                                                                   |
| `testgen/TestWorkerAgent`                                  | WORKHORSE   | One integration test class per task (Playwright/RestAssured/ProcessBuilder).                                |
| `testgen/TestReportGenerator`                              | LIGHTWEIGHT | LLM fix-suggestions for failed tests.                                                                       |
| `apitests/ApiTestSuiteAgent`                               | WORKHORSE   | OpenAPI 3.0 YAML generator for backend-only runs.                                                           |
| `optimiser/PromptOptimiserAgent`                           | WORKHORSE   | Surgical LLM-driven edits to prompt files (auto-commit disabled).                                           |
| `context/ContextAgent`                                     | LIGHTWEIGHT | Background context-doc maintenance via JSON patch on CODEBASE_CONTEXT.md.                                   |

Failover order (`application.yml`):
- `anthropic` → `openai`, `gemini`
- `gemini` → `openai`, `anthropic`
- `openai` → `gemini`, `anthropic`

---

## 4. Tools (V2 `tool/`)

Tool interface contract: `String name()`, `String description()`, `String inputSchema()`, `ToolResult execute(JsonNode args, ExecutionContext ctx)`.

| Class                          | Schema name      | Purpose                                                                                            |
| ------------------------------ | ---------------- | -------------------------------------------------------------------------------------------------- |
| `tool/ReadFileTool`            | `readFile`       | Reads file with optional 1-indexed line range; `project:` prefix for per-project workspace.        |
| `tool/WriteFileTool`           | `writeFile`      | Creates a NEW file only; sandbox-gated. Emits `RunEvent.fileWritten`.                              |
| `tool/EditFileTool`            | `editFile`       | Multi-edit string-replace on existing file; rejects if any oldStr missing or non-unique.           |
| `tool/ListFilesTool`           | `listFiles`      | Lists dir contents (optional recursive); hides build artifacts.                                    |
| `tool/SearchFilesTool`         | `searchFiles`    | Native Java walk + regex search; max 100 matches.                                                  |
| `tool/ShellTool`               | `shell`          | Sandboxed shell; 60s default / 300s max; container-routed for SWE-bench/ProgramBench.              |
| `tool/GitStatusTool`           | `gitStatus`      | `git status --short`.                                                                              |
| `tool/GitDiffTool`             | `gitDiff`        | Working-tree diff vs HEAD; truncates at 3KB.                                                       |
| `tool/GitCommitTool`           | `gitCommit`      | `git add -A` + vendored-purge + commit. Filtered from worker authority (`WORKER_DENY_TOOLS`).      |
| `tool/BuildProjectTool`        | `buildProject`   | Runs build via `BuildCommandResolver`. ProgramBench wraps with `./executable` hash-check.          |
| `tool/RunTestsTool`            | `runTests`       | Structured pytest tool for patch-mode; typed envelope `{summary, passed[], failed[], …}`.          |
| `tool/CompleteTaskTool`        | `completeTask`   | Structured task-done signal; WorkerAgent intercepts.                                               |

Supporting infrastructure:
- `tool/Tool` (interface).
- `tool/ToolResult` (sealed): `Ok(content) | Err(message, ErrorKind)`. `ErrorKind` enum: INVALID_ARGS, NOT_FOUND, IO_ERROR, FORBIDDEN, TIMEOUT, INTERNAL.
- `tool/ToolRegistry` — `toolNames`, `find`, `bind(ctx)`, `bindForWorker(ctx)` (excludes `gitCommit`), `toolDefinitions`. `ContextBoundToolCallback` adapter implements Spring AI `ToolCallback`.
- `tool/ExecutionContext` (record) — `runId, taskId, workspaceRoot`.
- `tool/PathSafety` — `static safeResolve(Path workspaceRoot, String relativePath)`.
- `tool/FileSandboxPolicy` — `validateWrite(workspaceRoot, target [, level]) → Decision`. MEDIUM allowlist of dirs + root files; HIGH adds dotfile allowlist.
- `tool/ShellSandboxPolicy` — `validate(command, workspacePath [, level]) → ValidationResult`. Allowlisted first-token; BLOCKED_COMMANDS / BLOCKED_PATTERNS at MEDIUM/HIGH.
- `tool/GitInvoker` — package-private `static GitResult run(workspace, args...)`.
- `tool/GitToolResult` (sealed `Ok | Err`).
- `tool/FileSystemTool` (V1 facade) — `@Tool` `readFile`/`writeFile`/`editFile`/`listFiles`/`searchFiles`; non-`@Tool` `readFileRange`, `listFilesRecursive`, `listFilesRecursivePerRepo`.
- `tool/GitTool` (V1 facade) — worktree lifecycle (`createWorktree`, `pruneAllWorktrees`, `removeWorktree`), `@Tool` `createBranch/commit/status/diff`, `mergeBranchIntoCurrent/IntoMain`, `rebaseBranchOnMain`, `commitIfDirty`, `getFullDiff`, `tagRun`, `abortMerge`, push helpers (`pushToRemote`, `pushToBranch`, `commitAndPushSubdir`, `pushToExistingBranch`, `pushToBranchBitbucket`).
- `tool/FileLockTool` — DB-backed per-file lock (dormant in V2).
- `tool/WorkspaceContextThreadLocalAccessor` — Micrometer `ThreadLocalAccessor<String>` bridging `WorkspaceContext`.

Smoke / Test tools:
- `tool/BuildProjectTool.wrapForExecutableCompileCheck` — ProgramBench-only `./executable` compile-check wrapper.
- `tool/RunTestsTool.containsShellMetachar` — defense-in-depth shell-metachar filter.

---

## 5. Repositories

Every `*Repository.java` with notable custom `@Query` methods called out.

### Run / state

- `state/repository/PipelineRunRepository` — `PipelineRun`. Atomic `@Modifying addTokens`, `updateStatusAndClearGate`, `updateApprovedExecutionMode`, `clearCurrentGate`. Notable `@Query`: `sumActualCostByUserAndSince/Project/Org`, `topUsersByRunCount`, `projectBreakdownByOrg/User`, `findRunsToArchive/Cleanup/TerminalRunsWithoutGcsKey`, `findArchivedRunsOlderThan`, `findExpiredRunsForTier`, `findSoftDeletedOlderThan`, `findOutageQueuedRuns`, `findRunsWithoutTitle`, `softDeleteByUserId` (GDPR), `findActiveByTriggeredByFinding` (V183), `aggregateCostAndTokens(Set<String>)` (PR-A).
- `state/repository/AgentTaskRepository` — `AgentTask`. `@Modifying lockIfPending(taskId, now)` (PENDING→LOCKED), `lockAndStart(taskId, model, branch, now)`.
- `state/repository/AgentMessageRepository` — `AgentMessage`.
- `state/repository/GateEventRepository` — `GateEvent`.
- `state/repository/CompactionEventRepository` — `CompactionEvent`.
- `state/repository/AgentInboxRepository` — `AgentInbox`. `@Modifying markDelivered(id, now)`.
- `state/repository/PlanVersionRepository` — `PlanVersion`.
- `state/repository/RunRepoRepository` — `RunRepo`.
- `state/repository/RunRepoSnapshotRepository` — `RunRepoSnapshot`.
- `state/repository/RunRecoveryRepository` — `RunRecovery`. `@Query findByClassificationIn`.
- `state/repository/IntegrationRunRepository` — `IntegrationRun`.
- `state/repository/IntegrationEventLogRepository` — `IntegrationEventLog`. `@Modifying deleteByCreatedAtBefore(cutoff)`.
- `state/repository/DeploymentRepository` — `Deployment`. `@Query countActiveByUserId`, `countByRunId`, `countActiveByRunId`.
- `state/repository/FileLockRepository` — `FileLock` (PK = filePath).
- `state/repository/MergeConflictRepository` — `MergeConflict`. Native `findTopConflictedFiles(limit)`, `getResolutionStats(runLimit)`.
- `state/repository/PromptAuditRepository` — `PromptAuditEntry`. `@Query findAllLatestPerFile()`.
- `agent/PromptHashAuditRepository` — `PromptAudit` (12-char hash audit).
- `harness/repository/GateRepository` — `GateRecord`. `@Modifying tryResolveIfPending(gateKey, status, now)`.

### Cost / pricing

- `pricing/ModelPricingRepository` — `ModelPricing` (`@EmbeddedId ModelPricingId(provider, model)`).
- `billing/cost/RunCostLogRepository` — `RunCostLog`.
- `cost/RunCostHistoryRepository` — `RunCostHistory`. `@Query findSimilarCosts(strategy, provider, repoCount, taskCount, Pageable)`, `findSimilarCostsLoose`.

### Billing

- `billing/repository/BillingPlanRepository` — `BillingPlan`.
- `billing/repository/BillingPlanLimitRepository` — `BillingPlanLimit`.
- `billing/repository/BillingSubscriptionRepository` — `BillingSubscription`. `findActiveByUserId` (JPQL), `findAllActiveOrPastDue` (B-08 bulk), `findLatestCustomerIdByUserId`, `countActiveFounderSubscriptions`, `findRenewingBefore`, `findCancelledPastPeriodEnd`, `findActiveByTier`.
- `billing/repository/BillingUsageRepository` — `BillingUsage`. `findCurrentByUserIdAndMetric`, `@Modifying incrementValue(id, delta, now)`.
- `billing/repository/RunTopupRepository` — `RunTopup`. `sumRemainingByUserId/OrgId(now)`, `findOldestActiveByUserId/OrgId`, `findByGatewayOrderId`, `@Modifying decrementRemaining(id, now)`, `expireOverdue(now)`, `findActiveByUserId`.
- `billing/repository/InvoiceRepository` — `Invoice`. `findByInvoiceNumber`, `findByGatewayPaymentId`, `findTopBySubscriptionIdOrderByInvoiceDateDesc`.
- `billing/repository/InvoiceLineItemRepository` — `InvoiceLineItem`.
- `billing/repository/InvoiceSequenceRepository` — `InvoiceSequence` (PK = fy). `@Lock(PESSIMISTIC_WRITE) findByFinancialYearForUpdate`.
- `billing/repository/TopupPackageRepository` — `TopupPackage`.
- `billing/repository/BillingProfileRepository` — `BillingProfile`.
- `billing/repository/SellerConfigRepository` — `SellerConfig`. default `getDefault()` throws IllegalState.
- `billing/repository/AccountingEntryRepository` — `AccountingEntry`. `getTrialBalance(from, to)` GROUP BY; `sumByAccountAndType`.
- `billing/repository/BillingWebhookEventRepository` — `BillingWebhookEvent`. `existsByGatewayAndEventId`.
- `billing/repository/WebhookEventLogRepository` — `WebhookEventLog`.
- `billing/repository/FounderPricingDiscountRepository` — `FounderPricingDiscount`. `@Lock(PESSIMISTIC_WRITE) findByIdForUpdate`.
- `billing/repository/FounderPricingAllocationRepository` — `FounderPricingAllocation`.

### Auth / org / project / payment

- `auth/repository/AppUserRepository` — `AppUser`. `@Lock(PESSIMISTIC_WRITE) findByIdForUpdate`, `findByOauthProviderAndOauthId`, `@Query findAllActive`, `findActiveSoloUsers`.
- `auth/repository/OrgRepository` — `Org`. `@Lock(PESSIMISTIC_WRITE) findByIdForUpdate`, `@Query findAllActive`.
- `auth/repository/OrgMemberRepository` — `OrgMember`. `countByOrgIdAndOrgRole`.
- `auth/repository/OrgInvitationRepository` — `OrgInvitation`.
- `auth/repository/EmailTokenRepository` — `EmailToken`. `@Modifying deleteExpired(now)`.
- `auth/repository/RefreshTokenBlocklistRepository` — `RefreshTokenBlocklist`. Native `@Modifying insertIfAbsent(jti, userId, expiresAt)` (ON CONFLICT DO NOTHING).
- `auth/repository/ProjectRepository` — `Project`. `findByOrgIdAndDeletedAtIsNull`, `countByOrgIdAndStatusAndDeletedAtIsNull`, `findByIsSandboxTrueAndStatus`, `findByDeletedAtBefore`.
- `auth/repository/ProjectMemberRepository` — `ProjectMember`. `@Query findProjectIdsByUserId(userId)`, `countByProjectIdAndProjectRole`.
- `auth/repository/TierConfigRepository` — `TierConfig`.
- `auth/repository/PaymentAuditRepository` — `PaymentAudit`. `@Query findByUserIdOrOrgId(userId, orgId)` (M-017 DSAR).
- `auth/repository/ActivityAuditRepository` — `ActivityAudit`. `@Query findChainTail(Pageable)`, `findChainOrdered()` (verifier walk).

### Project / codeintel / RAG / memory

- `project/repository/ProjectRepoRepository` — `ProjectRepo`.
- `project/repository/ProjectRepoLinkRepository` — `ProjectRepoLink`. `countByProjectId` (V140 cap), `existsByRepoId`.
- `project/template/ProjectSpecTemplateRepository` — `ProjectSpecTemplate`.
- `project/rag/repository/ProjectDocumentRepository` — `ProjectDocument`. `countByProjectIdAndStatusAndDeletedAtIsNull`.
- `project/pr/PullRequestRecordRepository` — `PullRequestRecord`. `findByRepoFullNameAndPrNumber`, `findByProjectIdAndKindNotOrderByCreatedAtDesc` (V169), `findFirstByRepoFullNameAndHeadBranchOrderByCreatedAtDesc` (CI autofix), open-only variants.
- `codeintel/repository/ProjectCodeMapRepository` — `ProjectCodeMap`.
- `codeintel/repository/RepoArchitectureDocRepository` — `RepoArchitectureDoc`. `@Query sumCostUsdByProject`.
- `codeintel/repository/ProjectArchitectureDocRepository` — `ProjectArchitectureDoc`. `@Query sumCostUsdByProject`.
- `codeintel/repository/ProjectGeneratedDocRepository` — `ProjectGeneratedDoc`. PR-Q cache lookup `findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc`.
- `codeintel/repository/FilePurposeSummaryRepository` — `FilePurposeSummary`.
- `codeintel/repository/RunCodeMapRepository` — `RunCodeMap`.
- `memory/repository/ProjectChatMessageRepository` — `ProjectChatMessage`. `@Modifying deleteByProjectId`.
- `memory/repository/ProjectMemorySnapshotRepository` — `ProjectMemorySnapshot`.
- `memory/repository/ProjectRunLedgerRepository` — `ProjectRunLedger`. `findTop10ByProjectIdAndCompactedIntoIsNullOrderByRunNumberDesc`, `@Query findUncompactedBeforeRunNumber`, `existsByRunId`.

### Execution / migration / readymade / sharing / referral / invariants / standards / contracts

- `execution/repository/ExecutionRunRepository` — `ExecutionRun`. `findListByProjectId`, `findDetailById`, `findStaleNonTerminal`, `countActiveByProjectId`, `findActiveByProjectId`, `sumTotalCostByUserAndSince/Project`, `findCompletedWithZeroTotals`.
- `execution/repository/ExecutionSolutionRepository` — `ExecutionSolution`. `findWithDependenciesByExecutionRun_Id...`, `findWithRepoMappingsByExecutionRun_Id...`.
- `execution/repository/ExecutionSolutionRunRepository` — `ExecutionSolutionRun`. `findPipelineRunIdsByExecutionRunId`.
- `execution/repository/ExecutionSolutionRepoRepository` — `ExecutionSolutionRepo`.
- `execution/repository/ExecutionContextHandoverRepository` — `ExecutionContextHandover`.
- `migration/repository/MigrationRecipeRepository` — `MigrationRecipe`.
- `migration/repository/MigrationVariantRepository` — `MigrationVariant`. `@Transactional void deleteByRecipeId`.
- `migration/repository/MigrationSlotRepository` — `MigrationSlot`. `@Transactional void deleteByRecipeId`.
- `migration/repository/MigrationDependencyRepository` — `MigrationDependency`. `@Transactional void deleteByRecipeId`.
- `migration/repository/MigrationDryRunCacheRepository` — `MigrationDryRunCache`.
- `readymade/repository/ReadymadeSolutionRepository` — `ReadymadeSolution`.
- `readymade/repository/ReadymadeVariantRepository` — `ReadymadeVariant`. `findByLanguageAndFramework`, `@Transactional void deleteBySolutionId`.
- `readymade/repository/ReadymadeSlotRepository` — `ReadymadeSlot`. `@Transactional void deleteBySolutionId`.
- `readymade/repository/ReadymadeDependencyRepository` — `ReadymadeDependency`. `@Transactional void deleteBySolutionId`.
- `readymade/repository/ReadymadeFavouriteRepository` — `ReadymadeFavourite`. `@Transactional void deleteByUserIdAndSolutionId`.
- `sharing/repository/ProjectShareLinkRepository` — `ProjectShareLink`. `@Query countActiveByProject(projectId, now)`.
- `sharing/repository/ProjectInviteRepository` — `ProjectInvite`.
- `referral/ReferralInviteRepository` — `ReferralInvite`. `@Query findByRefereeEmailIgnoreCase`, `findPendingByReferrer`, `findUngrantedByAcceptedUser`, `findByReferrerAndEmail`, `findByToken`.
- `arch/invariants/ArchInvariantRepository` — `ArchInvariant`.
- `arch/invariants/ArchInvariantViolationRepository` — `ArchInvariantViolation`. `@Query findRecentForProject`.
- `standards/OrgStandardRepository` — `OrgStandard`.
- `standards/ProjectStandardOverrideRepository` — `ProjectStandardOverride`.
- `standards/StandardsViolationRepository` — `StandardsViolation`. `@Query findRecentForProject`, `findRecentOverridesForStandard`.
- `standards/OrgConfigRepoRepository` — `OrgConfigRepo`.
- `contracts/ContractRegistryRepository` — `ContractRegistry`.
- `contracts/ContractVersionRepository` — `ContractVersion`.
- `contracts/ContractConsumerRepository` — `ContractConsumer`.
- `contracts/ContractDriftEventRepository` — `ContractDriftEvent`. `@Query findUnresolvedByContractId`.

### Code review / PR review / preview / standards-enforcement

- `codereview/repository/CodeReviewScanRepository` — `CodeReviewScan`. `@Query countByUserSince(userId, sinceMonthStart)`, `sumCostUsdByProject`, `sumTokensByProject`.
- `codereview/repository/CodeReviewIssueRepository` — `CodeReviewIssue`. `findByFingerprint`.
- `prreview/repository/PrReviewRepository` — `PrReview`. `findByRepoFullNameAndPrNumberAndHeadSha` (idempotency), `@Query countByUserSince`, `sumCostUsdByProject`, `sumTokensByProject`.
- `prreview/repository/PrReviewCommentRepository` — `PrReviewComment`. `countByPrReviewId`.
- `prreview/inbox/PrCommentInboxRepository` — `PrCommentInbox`. `findByRepoFullNameAndPrNumberAndCommentId` (dedup), `countByRunIdAndStatus`.
- `deployment/credential/DeploymentCredentialRepository` — `DeploymentCredential`. `@Query findUserDefault(userId, cloudProvider)` (project_id IS NULL).
- `deployment/preview/vm/PreviewRegistryRepository` — `PreviewRegistry`. `@Query avgDeployDurationLastN(n)`, `@Query countActiveByVm()`.
- `deployment/preview/vm/PreviewMetricRepository` — `PreviewMetric`.

### Folder / activity / saga / scoring / report / test / notification / Slack / analytics / router / ADR

- `folder/ProjectConfigRepository` — `ProjectConfig`.
- `activity/ActivityFeedEntryRepository` — `ActivityFeedEntry`. `@Query findRecentForProject(projectId, from, to)`, `findRecentForProjectLimited` (Pageable LIMIT).
- `saga/SideEffectOutboxRepository` — `SideEffectOutboxEntry`. `@Query findDue(now, limit)`, `@Modifying markProcessing(id)`.
- `scoring/repository/RunRubricRowRepository` — `RunRubricRow`. `existsByRunId`.
- `report/ValidationReportRepository` — `ValidationReport`. `findByShareToken`, native `findAllByProjectId` (Pilot R6), `countCompletedSince`, `verifiedScorePercentSince`, `countAllTime`, `findTopGalleryRuns(limit)`, `execSnapshotTotals(since)`, `spendByProjectSince(since)`, `topWinsSince(since)`.
- `testgen/repository/TestPlanRepository` — `TestPlan`. `findByRunId`.
- `testgen/repository/TestTaskRepository` — `TestTask`. `findByTestPlanIdAndStatus`.
- `testgen/repository/TestResultRepository` — `TestResult`. `countByTestPlanIdAndStatus`.
- `notification/NotificationRepository` — `Notification`. `existsByLinkAndType` (dedupe), `@Modifying markAllRead(userId)`, `markRead(id, userId)`.
- `notification/pref/UserNotificationPrefRepository` — `UserNotificationPref`.
- `integration/slack/model/OrgSlackIntegrationRepository` — `OrgSlackIntegration`. `findByOrgId`, `findByTeamId`, `deleteByOrgId`.
- `integration/slack/model/ProjectSlackChannelRepository` — `ProjectSlackChannel`. `deleteByOrgId`, `countByOrgId`.
- `integration/slack/model/SlackNotificationLogRepository` — `SlackNotificationLog`. `@Modifying deleteByCreatedAtBefore`.
- `analytics/UserActivationEventRepository` — `UserActivationEvent`. `@Query countByEventName`, `countByEventNameSince`.
- `router/ModelTaskMetricRepository` — `ModelTaskMetric`. `@Query findTopNOrderByCreatedAtDesc(Pageable)`; default int-arg helper.
- `adr/AdrRepository` — `Adr`. `@Query findAcceptedForProject(projectId)`, `findByRepoIdAndAdrIdInRepo`.

---

## 6. Entities

Format: `EntityName  @Table(name="...")  approx N columns  notable FK columns / migration notes`.

### Auth / org / users / projects

- `auth/entity/AppUser` — `app_user` — 39 cols. Encrypted: openai_api_key/anthropic_api_key/gemini_api_key/bitbucket_app_password/jira_api_token/confluence_api_token. Keys: `id @Id`, `email` unique. FK: org_id, sandbox_project_id, referred_by_user_id, oauth_provider+oauth_id.
- `auth/entity/Org` — `org` — 36 cols. V198 `external_sharing_enabled`. M-032 `require_admin_approval_above_usd`, M-039 `allowed_github_owners`. SSO + GitHub-App fields. Encrypted anthropic/openai/gemini/jira/confluence tokens. `is_personal` (V162).
- `auth/entity/OrgMember` — `org_member` — 5 cols — UniqueConstraint(org_id, user_id).
- `auth/entity/OrgInvitation` — `org_invitation` — 11 cols. Token unique.
- `auth/entity/Project` — `project` — 16 cols. FK: org_id, created_by. `default_reviewers jsonb`, `is_sandbox`, `archived_at`, `deleted_at` (V191), `config_storage` (E1.7).
- `auth/entity/ProjectMember` — `project_member` — 6 cols — UniqueConstraint(project_id, user_id). `is_external` (V175).
- `auth/entity/TierConfig` — `tier_config` — 25 cols — `tier String @Id`. Caps: monthly_run_limit, max_org_members, max_chat_messages, concurrent_run_limit, max_apply_suggestions, max_preview_starts, max_run_auto_retries, can_deploy, max_deployments, max_run_cost_usd, retention_days, max_projects_per_org, max_repos_per_project (V140), pr_review_enabled+quota (V172), code_review_enabled+quota (V172), share_link_active_cap (V175), lifecycle_state (v2.6.0).
- `auth/entity/ActivityAudit` — `activity_audit` — 13 cols — hash-chain `prev_hash`/`row_hash`/`chain_seq`.
- `auth/entity/PaymentAudit` — `payment_audit` — 18 cols — JSONB metadata.
- `auth/entity/RefreshTokenBlocklist` — `refresh_token_blocklist` — 4 cols — `jti String @Id`.
- `auth/entity/EmailToken` — `email_token` — 7 cols. Token unique.

### Run / pipeline / state

- `state/entity/PipelineRun` — `pipeline_run` — ~95 cols — `runId String @Id`. V107 status_reason, V109 plan_requirements_json, V130 hierarchical-planner phase costs, V155 selected_repo_ids jsonb, V162 personal-org, V168 pause fields, V178 user_cost_cap_usd, V182 run_mode, V183 triggered_by_finding, V188 pipeline_run_id (unified-dispatch chaining). `@Deprecated github_pr_url`. `@EntityListeners(TenantInterceptor.class)`.
- `state/entity/AgentTask` — `agent_task` — 36 cols — `taskId String @Id`. V109 meets_requirements_json, V130 task_type/expansion_status/expanded_at, V184 last_llm_failure TEXT, V190 failure_diagnostic_json.
- `state/entity/AgentMessage` — `agent_message` — 9 cols — `messageId String @Id`.
- `state/entity/AgentInbox` — `agent_inbox` — 10 cols.
- `state/entity/GateEvent` — `gate_event` — 6 cols.
- `state/entity/CompactionEvent` — `compaction_event` — 5 cols.
- `state/entity/PlanVersion` — `plan_version` — 6 cols.
- `state/entity/RunRepo` — `run_repo` — 9 cols. RepoMode enum (NEW/EXISTING/NONE).
- `state/entity/RunRepoSnapshot` — `run_repo_snapshot` — 6 cols. V100.
- `state/entity/RunRecovery` — `run_recovery` — 10 cols.
- `state/entity/IntegrationRun` — `integration_run` — 6 cols.
- `state/entity/IntegrationEventLog` — `integration_event_log` — 4 cols — unique(source_type, event_id).
- `state/entity/FileLock` — `file_lock` — 4 cols — `filePath String @Id`.
- `state/entity/MergeConflict` — `merge_conflict` — 10 cols — `Long id @Id`.
- `state/entity/Deployment` — `deployment` — ~30 cols. DeployStrategy/DeployStatus enums.
- `state/entity/PromptAuditEntry` — `prompt_audit_entry` — 11 cols.
- `harness/entity/GateRecord` — `gate_record` — 7 cols — `gateKey String @Id`.
- `agent/PromptAudit` — `prompt_audit` — 6 cols — 12-char hash audit.

### Cost / pricing

- `pricing/ModelPricing` — `model_pricing` — `@EmbeddedId ModelPricingId(provider, model)` + 4 cols.
- `billing/cost/RunCostLog` — `run_cost_log` — 18 cols.
- `cost/RunCostHistory` — `run_cost_history` — 19 cols — `run_id @Id`. PR-A V187.

### Billing

- `billing/entity/BillingPlan` — `billing_plan` — 22 cols. v2.6.0 lifecycle.
- `billing/entity/BillingPlanLimit` — `billing_plan_limit` — 4 cols.
- `billing/entity/BillingSubscription` — `billing_subscription` — 22 cols. FK: user_id, org_id, billing_plan_id. v2.6.x billing_entity_type/id.
- `billing/entity/BillingUsage` — `billing_usage` — 7 cols.
- `billing/entity/RunTopup` — `run_topup` — 13 cols.
- `billing/entity/Invoice` — `invoice` — 45 cols. India-GST compliant.
- `billing/entity/InvoiceLineItem` — `invoice_line_item` — 9 cols.
- `billing/entity/InvoiceSequence` — `invoice_sequence` — `financialYear String @Id`.
- `billing/entity/BillingProfile` — `billing_profile` — 17 cols.
- `billing/entity/SellerConfig` — `seller_config` — 11 cols — `id @Id` default "DEFAULT".
- `billing/entity/AccountingEntry` — `accounting_entry` — 11 cols.
- `billing/entity/BillingWebhookEvent` — `billing_webhook_event` — 8 cols.
- `billing/entity/WebhookEventLog` — `webhook_event_log` — 9 cols — unique(gateway, event_id).
- `billing/entity/TopupPackage` — `topup_package` — 9 cols.
- `billing/entity/FounderPricingDiscount` — `founder_pricing_discount` — 13 cols.
- `billing/entity/FounderPricingAllocation` — `founder_pricing_allocation` — 7 cols — unique(discount_id, user_id) (V177).

### Code review / PR review / preview / VM / project workbench

- `codereview/entity/CodeReviewScan` — `code_review_scan` — 19 cols.
- `codereview/entity/CodeReviewIssue` — `code_review_issue` — 14 cols. Fingerprint sha256-64.
- `prreview/entity/PrReview` — `pr_review` — 16 cols — unique(repo_full_name, pr_number, head_sha).
- `prreview/entity/PrReviewComment` — `pr_review_comment` — 11 cols.
- `prreview/inbox/PrCommentInbox` — `pr_comment_inbox` — 13 cols — `Long id @Id`.
- `deployment/credential/DeploymentCredential` — `deployment_credential` — 11 cols. AES-256-GCM encrypted_data bytea.
- `deployment/preview/vm/PreviewRegistry` — `preview_registry` — 14 cols.
- `deployment/preview/vm/PreviewMetric` — `preview_metrics` — 6 cols — `Long id @Id`.
- `project/entity/ProjectRepo` — `project_repo` — 19 cols. Scope ORG/USER; FolderStatus + PrMode enums. R-322 subdir, R-325 size_kb + attached_anyway, v2.5.2 webhook_installed, E1.1 executespec_folder_status/sha/bootstrap_pr_url, E1.4 executespec_pr_mode.
- `project/entity/ProjectRepoLink` — `project_repo_link` — 6 cols. relationships jsonb.
- `project/template/ProjectSpecTemplate` — `project_spec_template` — 10 cols.
- `project/pr/PullRequestRecord` — `pull_request_record` — 17 cols. Status OPEN/MERGED/CLOSED; Kind RUN/BOOTSTRAP/ARCHITECTURE/CONTRACT/CHAT_TOOL; CiCheckStatus (V186).
- `project/rag/entity/ProjectDocument` — `project_document` — 14 cols.

### Execution / arch / contracts / migration / readymade / sharing / standards / invariants / ADR

- `execution/entity/ExecutionRun` — `execution_run` — 28 cols — `id String @Id (36)`. `@Version`. V155 selected_repo_ids jsonb, V159 migration_recipe/variant_id, V160 readymade_solution/variant_id, V188 pipeline_run_id, V193 failure_reason TEXT.
- `execution/entity/ExecutionSolution` — `execution_solution` — 26 cols. EAGER `dependencies` (ManyToMany join `execution_solution_dependency`), `repoMappings` (OneToMany).
- `execution/entity/ExecutionSolutionRun` — `execution_solution_run` — 10 cols.
- `execution/entity/ExecutionContextHandover` — `execution_context_handover` — 6 cols.
- `execution/entity/ExecutionSolutionRepo` — `execution_solution_repo` — 7 cols.
- `codeintel/entity/ProjectCodeMap` — `project_code_map` — 7 cols.
- `codeintel/entity/ProjectArchitectureDoc` — `project_architecture_doc` — 9 cols. `generated_from_shas/branches` jsonb (V192).
- `codeintel/entity/RepoArchitectureDoc` — `repo_architecture_doc` — 12 cols. V174 cost_usd.
- `codeintel/entity/ProjectGeneratedDoc` — `project_generated_doc` — 12 cols. V195 source_arch_version + source_shas_fingerprint (PR-Q cache).
- `codeintel/entity/FilePurposeSummary` — `file_purpose_summary` — 10 cols. PR-N2 V196.
- `codeintel/entity/RunCodeMap` — `run_code_map` — 12 cols.
- `contracts/ContractRegistry` — `contract_registry` — 12 cols — unique(repo_id, name).
- `contracts/ContractVersion` — `contract_version` — 7 cols — unique(contract_id, registry_version).
- `contracts/ContractConsumer` — `contract_consumer` — 7 cols — unique(contract_id, consumer_repo_id, declared_in_project_id).
- `contracts/ContractDriftEvent` — `contract_drift_event` — 7 cols.
- `migration/entity/MigrationRecipe` — `migration_recipe` — 25 cols.
- `migration/entity/MigrationVariant` — `migration_variant` — 8 cols — unique(recipe_id, options_signature).
- `migration/entity/MigrationSlot` — `migration_slot` — 10 cols — unique(recipe_id, name).
- `migration/entity/MigrationDependency` — `migration_dependency` — 3 cols — `@IdClass`.
- `migration/entity/MigrationDryRunCache` — `migration_dry_run_cache` — 8 cols — composite PK (recipe_id, stack_signature).
- `readymade/entity/ReadymadeSolution` — `readymade_solution` — 23 cols.
- `readymade/entity/ReadymadeVariant` — `readymade_variant` — 10 cols.
- `readymade/entity/ReadymadeSlot` — `readymade_slot` — 10 cols — unique(solution_id, name).
- `readymade/entity/ReadymadeDependency` — `readymade_dependency` — 3 cols — `@IdClass`.
- `readymade/entity/ReadymadeFavourite` — `readymade_favourite` — 3 cols — `@IdClass`.
- `sharing/entity/ProjectShareLink` — `project_share_link` — 13 cols. Surface enum.
- `sharing/entity/ProjectInvite` — `project_invite` — 14 cols.
- `standards/OrgStandard` — `org_standard` — 7 cols — unique(org_id, standard_id).
- `standards/ProjectStandardOverride` — `project_standard_override` — 8 cols — unique(project_id, standard_id).
- `standards/StandardsViolation` — `standards_violation` — 14 cols.
- `standards/OrgConfigRepo` — `org_config_repo` — 5 cols — unique(org_id).
- `arch/invariants/ArchInvariant` — `arch_invariant` — 6 cols — unique(project_id, invariant_id).
- `arch/invariants/ArchInvariantViolation` — `arch_invariant_violation` — 5 cols.
- `adr/Adr` — `adr` — 17 cols — unique(repo_id, adr_id_in_repo).

### Memory / chat / RAG / activity / saga / folder / notification / Slack / referral / analytics / scoring / report / test / router

- `memory/entity/ProjectRunLedger` — `project_run_ledger` — 9 cols.
- `memory/entity/ProjectMemorySnapshot` — `project_memory_snapshot` — 9 cols.
- `memory/entity/ProjectChatMessage` — `project_chat_message` — 9 cols.
- `activity/ActivityFeedEntry` — `activity_feed` (partitioned) — 12 cols — `@IdClass(ActivityFeedEntryId)`. V133.
- `saga/SideEffectOutboxEntry` — `side_effect_outbox` — 10 cols.
- `folder/ProjectConfig` — `project_config` — 6 cols — unique(project_id, config_key). V134.
- `notification/Notification` — `notification` — 7 cols — `String id @Id`.
- `notification/pref/UserNotificationPref` — `user_notification_pref` — 7 cols — unique(user_id, event_type, channel).
- `integration/slack/model/OrgSlackIntegration` — `org_slack_integration` — 9 cols — `Long id @Id`.
- `integration/slack/model/ProjectSlackChannel` — `project_slack_channel` — 8 cols.
- `integration/slack/model/SlackNotificationLog` — `slack_notification_log` — 9 cols.
- `referral/ReferralInvite` — `referral_invite` — 8 cols. V153.
- `analytics/UserActivationEvent` — `user_activation_event` — 4 cols — `@IdClass(PK)`. V125.
- `scoring/entity/RunRubricRow` — `run_rubric_row` — 11 cols. V197.
- `report/entity/ValidationReport` — `validation_report` — 30 cols. V74 accuracy_* + V83 6-dim scores + V106 verified_score + V194 rubric_score.
- `testgen/model/TestPlan` — `test_plan` — 14 cols — `Long id @Id`.
- `testgen/model/TestTask` — `test_task` — 10 cols — `Long id @Id`.
- `testgen/model/TestResult` — `test_result` — 11 cols — `Long id @Id`.
- `router/ModelTaskMetric` — `model_task_metric` — 16 cols — `Long id @Id`.

---

## Application bootstrap

- `ExecutespecApplication` (`com.navneet.executespec`) — `@SpringBootApplication` excluding 10 Spring AI auto-configurations (Anthropic, Google GenAI chat/embedding, OpenAI chat/audio/embedding/image/moderation) so chat/embedding beans wired manually in `config/AgentModelConfig`. `@EnableScheduling`, `@EnableAsync`, `@EnableRetry`, `@EnableConfigurationProperties({DeploymentProperties.class, IntegrationProperties.class, ModelMatrixProperties.class})`.

## Verification

All API contracts, agent tier mappings, tool schemas, and entity schemas reflect the codebase as of `1.0.0-M1` / V202. Tier mappings driven by `application.yml model-matrix.agent-tier-map`. Default LLM provider is **anthropic** since 1.0.0-M1.

Known anomalies / engineering-honest notes:

- `state/StateStore.completeParentFromSubtasks` overwrites `provider`/`model` last-wins — no aggregation.
- `notification/RunCompletionNotifier.onQuotaWarning` builds `QUOTA_WARNING_<yyyy-MM>:<userId>` (~56 chars) but `Notification.type` is `length=30` — silent insert failure path.
- `router/ModelResolver.selectAdaptiveProvider` known tech-debt (Bug #1c) — strict-mode-bypass guard is JVM flag `executespec.strict-provider` + feature flag `routing.adaptive-routing.enabled` (default OFF).
- `codeintel/RunCodeMapService.summarise` bypasses `LlmRouter` (Anthropic-direct); also field-injects `AnthropicChatModel`.
- `assessment/FeatureAssessmentService` field-injects `AnthropicChatModel` (required=false).
- `prreview/PrReviewAgent` does NOT use `PromptAssembler` (loads via `ClassPathResource` directly).
- `agent/CostEstimator.computeCostForRun` builds a `stateStore.findRun` lambda whose body is empty (dead chain).
- `tool/FileSystemTool.searchFiles` shells out to `grep -r` (V1 facade) vs V2 `SearchFilesTool` (native Java walk).
- `tool/GitTool.pushToRemote/pushToBranch/commitAndPushSubdir/pushToBranchBitbucket` use `--force`; `pushToExistingBranch` is the safe (fast-forward) path used by apply-suggestion.
- Two parallel webhook tables (`webhook_event_log` audit vs `billing_webhook_event` idempotency marker) both unique on (gateway, event_id).
- `Deployment` mixes `String` ISO timestamps (`ttlExpiresAt`/`createdAt`/`deployedAt`/`deletedAt`) with `Instant` `auto_destroy_at`.
- `BillingPlan.prePersist` force-sets `active=true` on insert.
- `IntegrationStatusController` uses field `@Autowired` (violates project pattern).
- `FeasibilityController` always returns 410 (V1 retired).
- Ten legacy guard sites remain in `AccessGuardCoverageTest.KNOWN_LEGACY_DRIFT`.
- `tool/package-info.java` is stale relative to V2 unification pass (references retired `V2`-suffix names).
