# JAVADOC.md — ExecuteSpec API reference

> Reference doc for the public Java surface — REST controllers, orchestration-level services, agents, V2 tools, JPA repositories, and JPA entities.
>
> Companion to [`CODEMAP.md`](CODEMAP.md): CODEMAP is the package-level index, JAVADOC is the API contract. Compact and exhaustive — meant to be `grep`-d, not read end-to-end.
>
> **Versions:** Java 21 · Spring Boot 3.5.14 · Spring AI 1.1.6 · ExecuteSpec `1.0.0-M1` · Migration head `V199`.
>
> **Base package:** `com.navneet.executespec.*` — 72 top-level packages, 98 REST controllers, 80 JPA entities, 115 repositories, 17 agents, 12 worker tools.

---

## 1. Controllers (REST surface)

Each entry below lists base path, method-level mapping (verb + full path), and a compact `methodName(args...) → ReturnType` signature. Args drop annotations (`@PathVariable`, `@RequestBody`, `@RequestParam`, `@AuthenticationPrincipal`) — assume the obvious annotation for each.

### 1.1 Runs (lifecycle, conversation, SSE, timeline)

#### RunController
- Base: `/api/runs`
- File: `api/controller/RunController.java`

```
POST   /api/runs
  submit(AppUserDetails userDetails, RunRequest req) → ResponseEntity<RunSummary>
GET    /api/runs
  list(AppUserDetails userDetails, String status, String projectId, String scope, boolean archived, int page, int size) → ResponseEntity<Map<String,Object>>
GET    /api/runs/{runId}
  get(String runId, AppUserDetails userDetails) → ResponseEntity<RunDetail>
GET    /api/runs/{runId}/cost
  getLiveCost(String runId, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
POST   /api/runs/{runId}/approve
  approvePlan(String runId, ApprovalRequest req, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/clarify
  clarifyContext(String runId, ClarificationRequest req, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/merge
  approveMerge(String runId, ApprovalRequest req, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/retry
  retryRun(String runId, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/fork
  forkRun(String runId, AppUserDetails userDetails, ForkOverrides overrides) → ResponseEntity<RunSummary>
POST   /api/runs/{runId}/pause
  pauseRun(String runId, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/edit-and-restart
  editAndRestart(String runId, EditAndRestartRequest body, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/resume
  resumeRun(String runId, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/push-github
  pushToGitHub(AppUserDetails userDetails, String runId, String repoUrl, String repoMode) → ResponseEntity<Map<String,String>>
POST   /api/runs/{runId}/sync
  syncRun(AppUserDetails userDetails, String runId, String branch) → ResponseEntity<Map<String,Object>>
POST   /api/runs/{runId}/sync/original
  syncRunToOriginal(AppUserDetails userDetails, String runId) → ResponseEntity<Map<String,Object>>
POST   /api/runs/{runId}/push-scm
  pushToScm(AppUserDetails userDetails, String runId, String repoUrl, String repoMode) → ResponseEntity<Map<String,String>>
PATCH  /api/runs/{runId}/project
  updateRunProject(String runId, Map<String,String> body, AppUserDetails userDetails) → ResponseEntity<RunSummary>
DELETE /api/runs/{runId}
  cancel(String runId, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/archive
  archiveRun(String runId, AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
POST   /api/runs/{runId}/unarchive
  unarchiveRun(String runId, AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
POST   /api/runs/{runId}/soft-delete
  softDeleteRun(String runId, AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
POST   /api/runs/{runId}/restore
  restoreRun(String runId, AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
POST   /api/runs/{runId}/tasks/{taskId}/retry
  retryTask(String runId, String taskId, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
POST   /api/runs/{runId}/implement-suggestions
  implementSuggestions(String runId, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/rework
  reworkRun(String runId, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/reopen-merge
  reopenMerge(String runId, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/retry-validation
  retryValidation(String runId, AppUserDetails userDetails) → ResponseEntity<Void>
GET    /api/runs/{runId}/recovery
  getRecovery(String runId, AppUserDetails userDetails) → ResponseEntity<List<RecoveryResponse>>
GET    /api/runs/{runId}/messages
  getMessages(String runId, AppUserDetails userDetails) → ResponseEntity<List<MessageResponse>>
GET    /api/runs/{id}/download
  downloadWorkspace(String id, String format, AppUserDetails userDetails) → ResponseEntity<?>
GET    /api/runs/{id}/files
  listWorkspaceFiles(String id, AppUserDetails userDetails) → ResponseEntity<List<String>>
GET    /api/runs/{id}/files/status
  listWorkspaceFilesWithStatus(String id, AppUserDetails userDetails) → ResponseEntity<List<WorkspaceFileService.FileEntry>>
GET    /api/runs/{runId}/openapi.yaml
  getOpenApiSpec(String runId, AppUserDetails userDetails) → ResponseEntity<String>
GET    /api/runs/{id}/file
  readWorkspaceFile(String id, String path, AppUserDetails userDetails) → ResponseEntity<String>
GET    /api/runs/{id}/file/diff
  readWorkspaceFileDiff(String id, String path, AppUserDetails userDetails) → ResponseEntity<WorkspaceFileService.FileDiff>
GET    /api/runs/{runId}/plan-versions
  getPlanVersions(String runId, AppUserDetails userDetails) → ResponseEntity<List<PlanVersion>>
POST   /api/runs/{runId}/edit-plan
  editPlan(String runId, EditPlanRequest req, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
GET    /api/runs/{runId}/task-graph
  getTaskGraph(String runId, AppUserDetails userDetails) → ResponseEntity<List<TaskGraphNode>>
```

#### RunConversationController
- Base: `/api/runs` — post-run conversation + apply-suggestion loop
- File: `api/controller/RunConversationController.java`

```
GET    /api/runs/{runId}/chat
  getHistory(String runId, AppUserDetails userDetails) → ResponseEntity<List<ChatHistoryItem>>
POST   /api/runs/{runId}/chat
  sendMessage(String runId, ChatMessageBody body, AppUserDetails userDetails) → ResponseEntity<ChatResponseBody>
POST   /api/runs/{runId}/apply
  applySuggestion(String runId, ApplyBody body, AppUserDetails userDetails) → CompletableFuture<ResponseEntity<ApplyResponseBody>>
GET    /api/runs/{runId}/apply/status
  applyStatus(String runId, AppUserDetails userDetails) → ResponseEntity<ApplyResultStore.ApplyState>
POST   /api/runs/{runId}/iterate    (410 Gone — not implemented in V2)
```

#### AnonymousRunController
- Base: `/api/v3` — R-111 anonymous trial endpoint
- File: `api/AnonymousRunController.java`

```
POST   /api/v3/anonymous-runs
  submit(AnonymousRunRequest body, HttpServletRequest req) → ResponseEntity<AnonymousRunResponse>
GET    /api/v3/anonymous-runs/{sessionToken}
  status(String sessionToken) → ResponseEntity<AnonymousRunResponse>
```

#### UserRunsController
- Base: `/api`

```
GET    /api/user-runs-summary
  userRunsSummary(int days, AppUserDetails userDetails) → ResponseEntity<UserRunsSummaryData>
```

#### SseController
- Base: `/api/runs` — scoped 5-min SSE token + stream
- File: `api/controller/SseController.java`

```
GET    /api/runs/{runId}/sse-token
  getSseToken(String runId, AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
GET    /api/runs/{runId}/events
  subscribe(String runId, String token, String lastEventId, AppUserDetails userDetails) → SseEmitter
```

#### TimelineController
- Base: `/api`

```
GET    /api/runs/{runId}/timeline
  timeline(String runId, AppUserDetails userDetails) → ResponseEntity<TimelineData>
GET    /api/model-comparison
  modelComparison(int days, AppUserDetails userDetails) → ResponseEntity<ModelComparisonData>
```

#### ConversationController
- Base: `/api/conversation` — pre-flight requirement-refinement chat

```
POST   /api/conversation
  chat(ConversationRequest req, AppUserDetails userDetails) → ResponseEntity<ConversationResponse>
```

#### RolloutRunController
- Base: `/api/v2/runs` — rollout canary surface

```
POST   /api/v2/runs
  submit(SubmitRequest body) → ResponseEntity<Map<String,Object>>
GET    /api/v2/runs/{runId}
  poll(String runId) → ResponseEntity<Map<String,Object>>
```

### 1.2 Projects (workspace, repos, dashboard, scores, templates, RAG, sharing)

#### ProjectArchitectureController
- Project architecture pipeline + per-repo HLD/LLD
- File: `codeintel/ProjectArchitectureController.java`

```
POST   /api/projects/{projectId}/architecture/generate
  generate(AppUserDetails userDetails, String projectId) → ResponseEntity<ArchDocDto>
POST   /api/projects/{projectId}/analyse
  analyse(AppUserDetails userDetails, String projectId, String branch) → ResponseEntity<ArchDocDto>
GET    /api/projects/{projectId}/summary
  summary(String projectId, AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
GET    /api/projects/{projectId}/architecture/staleness
  staleness(String projectId, AppUserDetails userDetails) → ResponseEntity<StalenessService.StalenessReport>
GET    /api/projects/{projectId}/architecture
  latest(String projectId, AppUserDetails userDetails) → ResponseEntity<ArchDocDto>
GET    /api/projects/{projectId}/architecture/versions
  versions(String projectId, AppUserDetails userDetails) → ResponseEntity<List<ArchDocDto>>
GET    /api/projects/{projectId}/architecture/diff
  diff(String projectId, int from, int to, AppUserDetails userDetails) → ResponseEntity<ArchitectureDiffService.DiffReport>
PUT    /api/projects/{projectId}/architecture/annotate
  annotate(String projectId, Map<String,String> body, AppUserDetails userDetails) → ResponseEntity<ArchDocDto>
GET    /api/projects/{projectId}/repos/{repoId}/codemap
  codemap(String projectId, UUID repoId, AppUserDetails userDetails) → ResponseEntity<CodeMapDto>
GET    /api/projects/{projectId}/repos/{repoId}/architecture
  repoArchitecture(String projectId, UUID repoId, AppUserDetails userDetails) → ResponseEntity<RepoArchDto>
GET    /api/projects/{projectId}/repos/{repoId}/files
  listRepoFiles(String projectId, UUID repoId, AppUserDetails userDetails) → ResponseEntity<RepoFileListDto>
GET    /api/projects/{projectId}/repos/{repoId}/file
  readRepoFile(String projectId, UUID repoId, String path, AppUserDetails userDetails) → ResponseEntity<RepoFileDto>
```

#### ProjectDocGeneratorController

```
POST   /api/projects/{projectId}/docs/{docType}/generate
  generate(String projectId, String docType, AppUserDetails userDetails) → ResponseEntity<GeneratedDocDto>
GET    /api/projects/{projectId}/docs/{docType}
  latest(String projectId, String docType, AppUserDetails userDetails) → ResponseEntity<GeneratedDocDto>
GET    /api/projects/{projectId}/docs/{docType}/download
  download(String projectId, String docType, AppUserDetails userDetails) → ResponseEntity<String>
```

#### ProjectRepoController
- Per-org / per-project repo registry + GitHub webhook installation
- File: `project/controller/ProjectRepoController.java`

```
GET    /api/repos/github
  listGitHubRepos(AppUserDetails userDetails, String owner) → ResponseEntity<List<RemoteRepo>>
POST   /api/repos
  createRepo(AppUserDetails userDetails, CreateRepoRequest req) → ResponseEntity<ProjectRepoDto>
GET    /api/repos
  listRepos(AppUserDetails userDetails, String scope) → ResponseEntity<List<ProjectRepoDto>>
POST   /api/repos/{repoId}/sync
  syncRepo(AppUserDetails userDetails, UUID repoId) → ResponseEntity<ProjectRepoDto>
POST   /api/repos/{repoId}/webhook
  installRepoWebhook(AppUserDetails userDetails, UUID repoId) → ResponseEntity<Map<String,Object>>
DELETE /api/repos/{repoId}
  deleteRepo(AppUserDetails userDetails, UUID repoId) → ResponseEntity<Void>
GET    /api/projects/{projectId}/repos
  listProjectRepos(AppUserDetails userDetails, String projectId) → ResponseEntity<List<ProjectRepoLinkDto>>
POST   /api/projects/{projectId}/repos/{repoId}
  linkToProject(AppUserDetails userDetails, String projectId, UUID repoId) → ResponseEntity<ProjectRepoLinkDto>
DELETE /api/projects/{projectId}/repos/{repoId}
  unlinkFromProject(AppUserDetails userDetails, String projectId, UUID repoId) → ResponseEntity<Void>
PUT    /api/projects/{projectId}/repos/{repoId}/relationships
  updateRelationships(AppUserDetails userDetails, String projectId, UUID repoId, UpdateRelationshipsRequest req) → ResponseEntity<ProjectRepoLinkDto>
```

#### ProjectDashboardController

```
GET    /api/projects/{projectId}/dashboard
  dashboard(String projectId, AppUserDetails userDetails) → ResponseEntity<DashboardDto>
GET    /api/projects/{projectId}/workspace-health
  workspaceHealth(String projectId, AppUserDetails userDetails) → ResponseEntity<WorkspaceHealthService.HealthReport>
GET    /api/projects/{projectId}/post-merge-bar
  postMergeBar(String projectId, AppUserDetails userDetails) → ResponseEntity<List<PostMergeBarRow>>
GET    /api/projects/{projectId}/estimate
  estimate(String projectId, String requirement, AppUserDetails userDetails) → ResponseEntity<EstimationService.HeuristicEstimate>
GET    /api/projects/{projectId}/tech-debt
  techDebt(String projectId, int since, AppUserDetails userDetails) → ResponseEntity<TechDebtService.TechDebtReport>
```

#### ProjectScoresController

```
GET    /api/projects/{projectId}/scores
  scoresForProject(String projectId, AppUserDetails userDetails) → ResponseEntity<List<ScoreEntry>>
```

#### SpecTemplateController

```
GET    /api/projects/{projectId}/templates
  listForProject(String projectId, AppUserDetails user) → ResponseEntity<List<SpecTemplateService.TemplateDto>>
POST   /api/projects/{projectId}/templates
  createForProject(String projectId, SpecTemplateService.CreateRequest req, AppUserDetails user) → ResponseEntity<SpecTemplateService.TemplateDto>
POST   /api/orgs/{orgId}/templates
  createForOrg(String orgId, SpecTemplateService.CreateRequest req, AppUserDetails user) → ResponseEntity<SpecTemplateService.TemplateDto>
DELETE /api/projects/{projectId}/templates/{templateId}
  delete(String projectId, String templateId, AppUserDetails user) → ResponseEntity<Void>
```

#### ProjectChatController
- Base: `/api/projects/{projectId}/chat`

```
GET    /api/projects/{projectId}/chat
  history(AppUserDetails principal, String projectId) → ResponseEntity<List<ProjectChatMessage>>
GET    /api/projects/{projectId}/chat/info
  info(AppUserDetails principal, String projectId) → ResponseEntity<ChatInfo>
POST   /api/projects/{projectId}/chat
  send(AppUserDetails principal, String projectId, ChatRequest body) → ResponseEntity<ProjectChatMessage>
POST   /api/projects/{projectId}/chat/compile
  compile(AppUserDetails principal, String projectId) → ResponseEntity<Map<String,String>>
DELETE /api/projects/{projectId}/chat
  clear(AppUserDetails principal, String projectId) → ResponseEntity<Map<String,Integer>>
POST   /api/projects/{projectId}/chat/compact
  compact(AppUserDetails principal, String projectId) → ResponseEntity<ProjectChatService.CompactResult>
```

#### ProjectMemoryController
- Base: `/api/projects/{projectId}/memory`

```
GET    /api/projects/{projectId}/memory
  getMemory(String projectId, AppUserDetails userDetails) → ResponseEntity<ProjectMemoryResponse>
GET    /api/projects/{projectId}/memory/ledger
  getLedger(String projectId, AppUserDetails userDetails) → ResponseEntity<List<ProjectRunLedger>>
POST   /api/projects/{projectId}/memory/compact
  compact(String projectId, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
```

#### ProjectDocumentController
- Base: `/api/projects/{projectId}/documents` — pgvector RAG upload + async index

```
POST   /api/projects/{projectId}/documents
  upload(AppUserDetails principal, String projectId, MultipartFile file) → ResponseEntity<ProjectDocument>
GET    /api/projects/{projectId}/documents
  list(AppUserDetails principal, String projectId) → ResponseEntity<List<ProjectDocument>>
DELETE /api/projects/{projectId}/documents/{documentId}
  delete(AppUserDetails principal, String projectId, String documentId) → ResponseEntity<Map<String,String>>
```

#### ProjectExportController
- Base: `/api/projects/{projectId}/export` — bundle of every `.executespec/` artefact

#### ProjectLaunchController

```
GET    /api/projects/{projectId}/launches
  listLaunches(String projectId, int page, int size, AppUserDetails principal) → ResponseEntity<Page<ExecutionRunDto>>
GET    /api/projects/{projectId}/prs
  projectPrs(String projectId, AppUserDetails principal) → ResponseEntity<List<PrRollupEntry>>
GET    /api/projects/{projectId}/overview
  overview(String projectId, AppUserDetails principal) → ResponseEntity<ProjectOverview>
POST   /api/projects/{projectId}/launches/validate-repo-access
  validateOwner(String projectId, ValidateOwnerRequest body, AppUserDetails principal) → ResponseEntity<ValidateOwnerResult>
```

#### ProjectInviteController
- V175 + V198 cross-org invites — requires `org.external_sharing_enabled=true`

```
POST   /api/projects/{projectId}/invites
  create(String projectId, CreateInviteRequest req, AppUserDetails userDetails) → ResponseEntity<ProjectInviteDto>
GET    /api/projects/{projectId}/invites
  listPending(String projectId, AppUserDetails userDetails) → ResponseEntity<List<ProjectInviteDto>>
DELETE /api/projects/{projectId}/invites/{inviteId}
  revoke(String projectId, UUID inviteId, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/invites/accept
  accept(String token, AppUserDetails userDetails) → ResponseEntity<AcceptResponse>
GET    /api/invites/preview
  preview(String token) → ResponseEntity<InvitePreview>
```

#### ProjectShareLinkController
- V175 public read-only share links

```
POST   /api/projects/{projectId}/share-links
  create(String projectId, CreateShareLinkRequest req, AppUserDetails userDetails) → ResponseEntity<ShareLinkDto>
GET    /api/projects/{projectId}/share-links
  list(String projectId, AppUserDetails userDetails) → ResponseEntity<ShareLinkListResponse>
DELETE /api/projects/{projectId}/share-links/{linkId}
  revoke(String projectId, UUID linkId, AppUserDetails userDetails) → ResponseEntity<Void>
```

#### ProjectSharePublicController
- Public (no-auth) read endpoints, gated on a valid share-link token

```
GET    /api/shared/{token}/meta
  meta(String token) → ResponseEntity<SharedLinkMeta>
GET    /api/shared/{token}/run
  sharedRun(String token) → ResponseEntity<SharedRun>
GET    /api/shared/{token}/validation-report
  sharedReport(String token) → ResponseEntity<SharedValidationReport>
GET    /api/shared/{token}/code-review
  sharedCodeReview(String token) → ResponseEntity<SharedCodeReview>
GET    /api/shared/{token}/pr-review
  sharedPrReview(String token) → ResponseEntity<SharedPrReview>
GET    /api/shared/{token}/architecture
  sharedArchitecture(String token) → ResponseEntity<SharedArchitecture>
```

### 1.3 Auth (JWT, OAuth2, org, project membership, DSAR, SSO)

#### AuthController
- Base: `/api/auth`

```
POST   /api/auth/register
  register(RegisterRequest req) → ResponseEntity<UserDto>
POST   /api/auth/login
  login(LoginRequest req, HttpServletResponse response) → ResponseEntity<LoginResponse>
POST   /api/auth/refresh
  refresh(String refreshToken, HttpServletResponse response) → ResponseEntity<LoginResponse>
DELETE /api/auth/logout
  logout(String refreshToken, HttpServletResponse response) → ResponseEntity<Void>
GET    /api/auth/verify-email
  verifyEmail(String token, HttpServletResponse response) → ResponseEntity<LoginResponse>
POST   /api/auth/resend-verification
  resendVerification(Map<String,String> body) → ResponseEntity<Map<String,String>>
POST   /api/auth/forgot-password
  forgotPassword(Map<String,String> body) → ResponseEntity<Map<String,String>>
POST   /api/auth/reset-password
  resetPassword(Map<String,String> body) → ResponseEntity<Map<String,String>>
POST   /api/auth/change-password
  changePassword(AppUserDetails userDetails, ChangePasswordRequest req) → ResponseEntity<Map<String,String>>
GET    /api/auth/me
  me(AppUserDetails userDetails) → ResponseEntity<UserDto>
POST   /api/auth/invalidate-sessions
  invalidateSessions(AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
GET    /api/auth/me/permissions
  myPermissions(AppUserDetails userDetails) → ResponseEntity<TierPermissionService.TierPermissions>
GET    /api/auth/me/data-export
  exportMyData(AppUserDetails userDetails) → ResponseEntity<byte[]>
DELETE /api/auth/me/data
  eraseMyData(AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
PUT    /api/auth/settings/github
  updateGitHubSettings(AppUserDetails userDetails, GitHubSettingsRequest req) → ResponseEntity<UserDto>
PUT    /api/auth/settings/bitbucket
  updateBitbucketSettings(AppUserDetails userDetails, BitbucketSettingsRequest req) → ResponseEntity<UserDto>
PUT    /api/auth/settings/ai-keys
  updateAiKeySettings(AppUserDetails userDetails, AiKeySettingsRequest req) → ResponseEntity<UserDto>
PUT    /api/auth/settings/jira
  updateJiraSettings(AppUserDetails userDetails, JiraSettingsRequest req) → ResponseEntity<UserDto>
PUT    /api/auth/settings/confluence
  updateConfluenceSettings(AppUserDetails userDetails, ConfluenceSettingsRequest req) → ResponseEntity<UserDto>
PUT    /api/auth/settings/view-mode
  updateViewMode(AppUserDetails userDetails, ViewModeRequest req) → ResponseEntity<UserDto>
POST   /api/auth/settings/ai-keys/validate
  validateAiKey(ValidateAiKeyRequest req) → ResponseEntity<ValidationResult>
POST   /api/auth/settings/github/validate
  validateGitHub(ValidateGitHubRequest req) → ResponseEntity<ValidationResult>
POST   /api/auth/settings/bitbucket/validate
  validateBitbucket(ValidateBitbucketRequest req) → ResponseEntity<ValidationResult>
POST   /api/auth/settings/jira/validate
  validateJira(ValidateJiraRequest req) → ResponseEntity<ValidationResult>
POST   /api/auth/settings/confluence/validate
  validateConfluence(ValidateConfluenceRequest req) → ResponseEntity<ValidationResult>
```

#### OrgController
- Base: `/api/org`

```
POST   /api/org
  createOrg(AppUserDetails userDetails, CreateOrgRequest req) → ResponseEntity<OrgDto>
GET    /api/org/me
  getMyOrg(AppUserDetails userDetails) → ResponseEntity<OrgDto>
GET    /api/org/me/members
  listMembers(AppUserDetails userDetails) → ResponseEntity<List<OrgMemberDto>>
POST   /api/org/accept-invite
  acceptInvite(String token, AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
```

#### ProjectController
- Base: `/api/projects` — read-side only; mutations live on `ProjectInviteController`, `ProjectRepoController`, etc.

```
GET    /api/projects
  listProjects(AppUserDetails userDetails) → ResponseEntity<List<ProjectDto>>
GET    /api/projects/{id}
  getProject(AppUserDetails userDetails, String id) → ResponseEntity<ProjectDto>
GET    /api/projects/{id}/members
  listMembers(String id, AppUserDetails userDetails) → ResponseEntity<List<ProjectMemberDto>>
```

#### DsarController
- Base: `/api/dsar` — GDPR data subject access request

```
GET    /api/dsar/me
  exportSelf(AppUserDetails principal) → ResponseEntity<Map<String,Object>>
```

#### SsoConfigController
- Base: `/api/auth/sso` — SAML / OIDC SSO config (admin-only)

### 1.4 Billing (subscriptions, invoices, accounting, webhooks, audit)

#### BillingController
- Base: `/api/billing`

```
GET    /api/billing/plans
  listPlans(String keyMode, boolean includeHidden, AppUserDetails userDetails) → ResponseEntity<List<PlanResponse>>
GET    /api/billing/founder-pricing/{tier}
  getFounderOffer(String tier) → ResponseEntity<FounderOfferDto>
GET    /api/billing/status
  getStatus(AppUserDetails userDetails) → ResponseEntity<BillingStatusResponse>
POST   /api/billing/subscribe
  subscribe(CreateSubscriptionRequest request, AppUserDetails userDetails) → ResponseEntity<SubscriptionInitResult>
POST   /api/billing/verify
  verify(VerifyPaymentRequest request, AppUserDetails userDetails) → ResponseEntity<BillingStatusResponse>
POST   /api/billing/cancel
  cancel(AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/billing/seats
  updateSeats(UpdateSeatsRequest request, AppUserDetails userDetails) → ResponseEntity<BillingStatusResponse>
GET    /api/billing/topup/packages
  listTopupPackages() → ResponseEntity<List<TopupPackage>>
POST   /api/billing/topup/purchase
  purchaseTopup(TopupPurchaseRequest request, AppUserDetails userDetails) → ResponseEntity<TopupOrderInitResult>
POST   /api/billing/topup/verify
  verifyTopup(TopupVerifyRequest request, AppUserDetails userDetails) → ResponseEntity<BillingStatusResponse>
```

#### BillingWebhookController
- Base: `/api/webhooks` — HMAC-verified, no auth

```
POST   /api/webhooks/razorpay
  handleRazorpayWebhook(String rawPayload, String signature) → ResponseEntity<Void>
POST   /api/webhooks/stripe
  handleStripeWebhook(String rawPayload, String signature) → ResponseEntity<Void>
```

#### InvoiceController

```
GET    /api/billing/profile
  getProfile(AppUserDetails principal) → BillingProfile
PUT    /api/billing/profile
  updateProfile(AppUserDetails principal, BillingProfile body) → BillingProfile
GET    /api/billing/invoices
  myInvoices(AppUserDetails principal, Pageable pageable) → Page<Invoice>
GET    /api/billing/invoices/{id}
  myInvoiceDetail(AppUserDetails principal, UUID id) → InvoiceDetail
GET    /api/billing/invoices/{id}/pdf
  downloadPdf(AppUserDetails principal, UUID id) → ResponseEntity<?>
GET    /api/admin/invoices
  allInvoices(AppUserDetails principal, Pageable pageable) → Page<Invoice>
GET    /api/admin/invoices/{id}/pdf
  adminDownloadPdf(AppUserDetails principal, UUID id) → ResponseEntity<byte[]>
POST   /api/admin/invoices/{id}/credit-note
  issueCreditNote(AppUserDetails principal, UUID id, CreditNoteRequest req) → Invoice
POST   /api/admin/invoices/{id}/void
  voidInvoice(AppUserDetails principal, UUID id) → Invoice
POST   /api/admin/invoices/{id}/send
  resendInvoice(AppUserDetails principal, UUID id) → void
GET    /api/admin/seller-config
  getSellerConfig(AppUserDetails principal) → SellerConfig
PUT    /api/admin/seller-config
  updateSellerConfig(AppUserDetails principal, SellerConfig body) → SellerConfig
```

#### AccountingController
- Base: `/api/accounting` — India GST + double-entry ledger

```
GET    /api/accounting/revenue
  getRevenue(AppUserDetails principal, LocalDate from, LocalDate to) → RevenueMetrics
GET    /api/accounting/gst-summary
  getGstSummary(AppUserDetails principal, String fy, int quarter) → GstSummary
GET    /api/accounting/gstr1-export
  exportGstr1(AppUserDetails principal, String fy, int quarter) → ResponseEntity<byte[]>
GET    /api/accounting/trial-balance
  getTrialBalance(AppUserDetails principal, LocalDate from, LocalDate to) → List<TrialBalanceEntry>
GET    /api/accounting/entries
  getEntries(AppUserDetails principal, LocalDate from, LocalDate to, Pageable pageable) → Page<AccountingEntry>
```

#### PaymentAuditController
- Base: `/api/admin/payment-audit` (config-resolved) — SUPER_ADMIN-only audit log + CSV export

#### AdminBillingController
- Base: `/api/admin/billing`

```
POST   /api/admin/billing/seed-plans
  seedPlans() → ResponseEntity<String>
```

#### TierLifecycleController
- Base: `/api/admin` — LISTED / HIDDEN / SUNSETTING state machine

```
GET    /api/admin/tiers
  listTiers() → ResponseEntity<List<TierLifecycleService.TierLifecycleDto>>
PUT    /api/admin/tiers/{tier}/lifecycle
  updateTierLifecycle(AppUserDetails userDetails, String tier, LifecycleUpdateRequest req) → ResponseEntity<TierConfig>
PUT    /api/admin/billing-plans/{planId}/lifecycle
  updatePlanLifecycle(AppUserDetails userDetails, UUID planId, PlanLifecycleUpdateRequest req) → ResponseEntity<BillingPlan>
```

### 1.5 Code review + PR review (Phase 1 + Phase 4 autofix)

#### CodeReviewController

```
POST   /api/code-reviews
  submit(AppUserDetails userDetails, SubmitCodeReviewRequest req) → ResponseEntity<CodeReviewScanDto>
POST   /api/code-reviews/{id}/autofix
  autofix(UUID id, AutofixRequest req, AppUserDetails userDetails) → ResponseEntity<AutofixResponse>
GET    /api/code-reviews/{id}
  get(UUID id, AppUserDetails userDetails) → ResponseEntity<CodeReviewScanWithIssues>
GET    /api/projects/{projectId}/code-reviews
  listForProject(String projectId, AppUserDetails userDetails) → ResponseEntity<List<CodeReviewScanDto>>
GET    /api/projects/{projectId}/code-reviews/latest
  latest(String projectId, String repoFullName, AppUserDetails userDetails) → ResponseEntity<CodeReviewScanWithIssues>
GET    /api/code-reviews/{id}/report
  downloadReport(UUID id, AppUserDetails userDetails) → ResponseEntity<String>
```

#### PrReviewController

```
POST   /api/pr-reviews
  submit(AppUserDetails userDetails, SubmitPrReviewRequest req) → ResponseEntity<PrReviewDto>
POST   /api/pr-reviews/{id}/autofix
  autofix(UUID id, AppUserDetails userDetails) → ResponseEntity<AutofixResponse>
GET    /api/pr-reviews/{id}
  get(UUID id, AppUserDetails userDetails) → ResponseEntity<PrReviewWithComments>
GET    /api/pr-reviews
  listForPr(AppUserDetails userDetails, String repoFullName, int prNumber) → ResponseEntity<List<PrReviewDto>>
GET    /api/projects/{projectId}/pr-reviews
  listForProject(String projectId, AppUserDetails userDetails) → ResponseEntity<List<PrReviewDto>>
```

#### PrCommentInboxController
- Base: `/api/runs/{runId}/pr-comments`

```
GET    /api/runs/{runId}/pr-comments
  list(String runId, AppUserDetails user) → List<Dto>
GET    /api/runs/{runId}/pr-comments/pending-count
  pendingCount(String runId, AppUserDetails user) → ResponseEntity<PendingCount>
POST   /api/runs/{runId}/pr-comments/{commentId}/dismiss
  dismiss(String runId, Long commentId, AppUserDetails user) → ResponseEntity<Dto>
POST   /api/runs/{runId}/pr-comments/{commentId}/apply
  apply(String runId, Long commentId, AppUserDetails user) → ResponseEntity<ApplyResponse>
```

### 1.6 PRs + GitHub integration

#### PullRequestController

```
GET    /api/runs/{runId}/prs
  listPrsForRun(String runId, AppUserDetails userDetails) → ResponseEntity<List<PrSummary>>
POST   /api/projects/{projectId}/prs/refresh
  refreshProjectPrs(String projectId, boolean forceFull) → ResponseEntity<PrRefreshService.RefreshResult>
GET    /api/prs/snapshot
  snapshot(AppUserDetails userDetails, String repoFullName, int prNumber, int maxChars) → ResponseEntity<GitHubRepoService.PullRequestSnapshot>
```

#### GitHubWebhookController
- HMAC SHA-256 verified; no auth

```
POST   /webhooks/github
  receive(String signature, String event, String body) → ResponseEntity<Void>
```

#### GitHubAppController
- Base: `/api/auth/github-app` — GitHub App OAuth installation

### 1.7 Deployments + previews

#### DeploymentController
- Base: `/api/deployments` — Cloud Run permanent deployments

```
POST   /api/deployments
  deploy(DeployRequest request, AppUserDetails userDetails) → ResponseEntity<DeploymentSummary>
GET    /api/deployments
  list(String runId, AppUserDetails userDetails) → List<DeploymentSummary>
GET    /api/deployments/{id}
  detail(String id, AppUserDetails userDetails) → DeploymentDetail
DELETE /api/deployments/{id}
  delete(String id, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/deployments/{id}/rollback
  rollback(String id, AppUserDetails userDetails) → ResponseEntity<Void>
GET    /api/deployments/live
  live(AppUserDetails userDetails) → List<DeploymentSummary>
POST   /api/deployments/analyze
  analyze(String runId, AppUserDetails userDetails) → ResponseEntity<?>
POST   /api/deployments/confirm
  confirm(DeployConfirmRequest request, AppUserDetails userDetails) → ResponseEntity<DeploymentSummary>
POST   /api/deployments/{id}/engine-rollback
  engineRollback(String id, AppUserDetails userDetails) → ResponseEntity<Void>
DELETE /api/deployments/{id}/engine-destroy
  engineDestroy(String id, AppUserDetails userDetails) → ResponseEntity<Void>
```

#### PreviewController
- Base: `/api/runs` — ephemeral 5-min preview (Cloud Run or Docker)

```
GET    /api/runs/{runId}/preview/analyze
  analyze(String runId, AppUserDetails userDetails) → ResponseEntity<PreviewAnalysisDto>
GET    /api/runs/{runId}/preview/status
  getStatus(String runId, AppUserDetails userDetails) → ResponseEntity<PreviewStatusDto>
POST   /api/runs/{runId}/preview/start
  start(String runId, PreviewStartRequest request, AppUserDetails userDetails) → ResponseEntity<PreviewStatusDto>
POST   /api/runs/{runId}/preview/rebuild
  rebuild(String runId, AppUserDetails userDetails) → ResponseEntity<PreviewStatusDto>
DELETE /api/runs/{runId}/preview/stop
  stop(String runId, AppUserDetails userDetails) → ResponseEntity<Void>
GET    /api/runs/{runId}/preview/logs
  getLogs(String runId, int lines, String filter, AppUserDetails userDetails) → ResponseEntity<PreviewLogsDto>
GET    /api/runs/{runId}/preview/probe-api-docs
  probeApiDocs(String runId, AppUserDetails userDetails) → ResponseEntity<ApiDocsProbeResult>
POST   /api/runs/{runId}/preview/exec
  execInContainer(String runId, ExecRequest request, AppUserDetails userDetails) → ResponseEntity<ExecResponse>
```

#### PreviewVmController
- Base: `/api/v1/previews` — VM-pool admin

```
DELETE /api/v1/previews/{previewId}
  teardown(String previewId) → ResponseEntity<Void>
POST   /api/v1/previews/{previewId}/extend
  extend(String previewId, int minutes) → ResponseEntity<Void>
GET    /api/v1/previews/active
  listActive() → ResponseEntity<List<PreviewRegistry>>
GET    /api/v1/previews/stats
  stats() → ResponseEntity<Map<String,Object>>
GET    /api/v1/previews/stats/vm
  vmStats() → ResponseEntity<List<Map<String,Object>>>
```

#### CredentialController
- Base: `/api/credentials/gcp` — encrypted GCP service-account JSON storage

```
GET    /api/credentials/gcp/required-permissions
  requiredPermissions() → ResponseEntity<List<CredentialService.RequiredPermission>>
POST   /api/credentials/gcp
  storeCredential(AppUserDetails userDetails, String projectId, String serviceAccountJson) → ResponseEntity<CredentialService.CredentialSummary>
GET    /api/credentials/gcp
  getCredential(AppUserDetails userDetails, String projectId) → ResponseEntity<?>
GET    /api/credentials/gcp/all
  listCredentials(AppUserDetails userDetails) → ResponseEntity<List<CredentialService.CredentialSummary>>
GET    /api/credentials/gcp/validate
  validateCredential(AppUserDetails userDetails, String projectId) → ResponseEntity<CredentialService.ValidationResult>
DELETE /api/credentials/gcp
  deleteCredential(AppUserDetails userDetails, String projectId) → ResponseEntity<Map<String,String>>
```

### 1.8 Validation reports + scoring + test execution

#### ValidationReportController
- Base: `/api`

```
GET    /api/runs/{runId}/report
  getReport(String runId, AppUserDetails userDetails) → ResponseEntity<ValidationReportDTO>
POST   /api/runs/{runId}/report/share
  shareReport(String runId, AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
GET    /api/runs/{runId}/report/pdf
  downloadPdf(String runId, AppUserDetails userDetails) → ResponseEntity<byte[]>
GET    /api/reports/shared/{shareToken}
  getSharedReport(String shareToken) → ResponseEntity<ValidationReportShareDTO>
```

#### TestGenController

```
GET    /api/runs/{runId}/test-plan
  getTestPlan(String runId, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
GET    /api/runs/{runId}/test-graph
  getTestGraph(String runId, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
```

#### TestResultController

```
GET    /api/runs/{runId}/test-report
  getTestReport(String runId, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
PUT    /api/test-results/{id}/mark-invalid
  markInvalid(Long id, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/runs/{runId}/run-tests
  runTests(String runId, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
POST   /api/runs/{runId}/apply-test-fixes
  applyTestFixes(String runId, String signature, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
```

### 1.9 Admin (super admin, tier console, feature flags, reconciliation, analytics)

#### SuperAdminController
- Base: `/api/admin` — broad SUPER_ADMIN-gated surface

```
GET    /api/admin/guardrail
  currentGuardrail() → ResponseEntity<Map<String,Object>>
POST   /api/admin/executions/reconcile
  reconcileExecutions(Map<String,Object> body) → ResponseEntity<AdminReconciliationService.Result>
POST   /api/admin/validation-reports/backfill-verified-score
  backfillVerifiedScore() → ResponseEntity<VerifiedScoreBackfillService.Result>
POST   /api/admin/runs/{runId}/tasks/{taskId}/force-fail
  forceFailTask(String runId, String taskId, Map<String,String> body, AppUserDetails admin) → ResponseEntity<Map<String,Object>>
POST   /api/admin/runs/{runId}/re-review
  reReviewRun(String runId, AppUserDetails admin) → ResponseEntity<Map<String,Object>>
GET    /api/admin/audit-chain/verify
  verifyAuditChain() → ResponseEntity<ActivityAuditService.ChainVerifyReport>
POST   /api/admin/runs/halt-all
  haltAllActiveRuns(AppUserDetails admin, Map<String,String> body) → ResponseEntity<Map<String,Object>>
POST   /api/admin/prompts/{name}/rollback
  rollbackPrompt(String name) → ResponseEntity<Map<String,Object>>
GET    /api/admin/prompts/{name}/history
  promptHistory(String name) → ResponseEntity<List<PromptAuditEntry>>
POST   /api/admin/usage-reports/trigger
  triggerUsageReports(Map<String,String> body) → ResponseEntity<Map<String,Object>>
GET    /api/admin/users
  listUsers() → ResponseEntity<List<UserDto>>
GET    /api/admin/users/{id}
  getUser(String id) → ResponseEntity<UserDto>
PUT    /api/admin/users/{id}/status
  updateUserStatus(AppUserDetails adminDetails, String id, Map<String,String> body) → ResponseEntity<UserDto>
PUT    /api/admin/users/{id}/tier
  updateUserTier(AppUserDetails adminDetails, String id, Map<String,String> body) → ResponseEntity<UserDto>
PUT    /api/admin/users/{id}/role
  updateUserRole(AppUserDetails adminDetails, String id, Map<String,String> body) → ResponseEntity<UserDto>
GET    /api/admin/orgs
  listOrgs() → ResponseEntity<List<OrgDto>>
GET    /api/admin/orgs/{id}
  getOrg(String id) → ResponseEntity<OrgDto>
PUT    /api/admin/orgs/{id}/status
  updateOrgStatus(AppUserDetails adminDetails, String id, Map<String,String> body) → ResponseEntity<OrgDto>
PUT    /api/admin/orgs/{id}/tier
  updateOrgTier(AppUserDetails adminDetails, String id, Map<String,String> body) → ResponseEntity<OrgDto>
PUT    /api/admin/orgs/{id}/run-limit
  updateOrgRunLimit(AppUserDetails adminDetails, String id, Map<String,Object> body) → ResponseEntity<OrgDto>
PUT    /api/admin/orgs/{id}/api-keys
  updateOrgApiKeys(AppUserDetails adminDetails, String id, Map<String,Boolean> body) → ResponseEntity<OrgDto>
GET    /api/admin/tier-config
  listTierConfig() → ResponseEntity<List<TierConfig>>
PUT    /api/admin/tier-config/{tier}
  updateTierConfig(String tier, Map<String,Integer> body) → ResponseEntity<TierConfig>
POST   /api/admin/invite
  adminInvite(AppUserDetails adminDetails, AdminInviteRequest req) → ResponseEntity<Map<String,String>>
GET    /api/admin/invitations/escalated
  listEscalated() → ResponseEntity<List<OrgInvitation>>
POST   /api/admin/invitations/{id}/approve
  approveInvitation(AppUserDetails adminDetails, String id) → ResponseEntity<Map<String,String>>
POST   /api/admin/invitations/{id}/reject
  rejectInvitation(AppUserDetails adminDetails, String id) → ResponseEntity<Map<String,String>>
GET    /api/admin/audit-logs
  getAuditLogs(String entityType, String eventType, String actorId, String entityId, String from, String to, int page, int size) → ResponseEntity<AuditLogPage>
POST   /api/admin/test-email
  sendTestEmail(Map<String,String> body) → ResponseEntity<Map<String,String>>
POST   /api/admin/users/{id}/grant-runs
  grantUserRuns(AppUserDetails adminDetails, String id, Map<String,Object> body) → ResponseEntity<RunTopup>
POST   /api/admin/orgs/{id}/grant-runs
  grantOrgRuns(AppUserDetails adminDetails, String id, Map<String,Object> body) → ResponseEntity<RunTopup>
GET    /api/admin/topup-packages
  listTopupPackages() → ResponseEntity<List<TopupPackage>>
POST   /api/admin/topup-packages
  createTopupPackage(Map<String,Object> body) → ResponseEntity<TopupPackage>
PUT    /api/admin/topup-packages/{id}
  updateTopupPackage(UUID id, Map<String,Object> body) → ResponseEntity<TopupPackage>
DELETE /api/admin/topup-packages/{id}
  deleteTopupPackage(UUID id) → ResponseEntity<Map<String,String>>
```

#### ConfigAdminController
- Base: `/api/admin/config`

```
GET    /api/admin/config                 listConfig() → ResponseEntity<Map<String,Object>>
PUT    /api/admin/config/{key}           putConfig(String key, Map<String,String> body) → ResponseEntity<Map<String,String>>
DELETE /api/admin/config/{key}           deleteConfig(String key) → ResponseEntity<Map<String,String>>
GET    /api/admin/config/secrets         listSecrets() → ResponseEntity<Map<String,Object>>
PUT    /api/admin/config/secrets/{name}  putSecret(String name, Map<String,String> body) → ResponseEntity<Map<String,String>>
POST   /api/admin/config/refresh         refresh() → ResponseEntity<Map<String,Object>>
```

#### FeatureFlagController
- Base: `/api/admin/feature-flags`

```
GET    /api/admin/feature-flags
  list() → ResponseEntity<List<FeatureFlagDto>>
GET    /api/admin/feature-flags/public
  listPublic(AppUserDetails userDetails) → ResponseEntity<Map<String,Boolean>>
PUT    /api/admin/feature-flags/{key}
  update(String key, Map<String,String> body, AppUserDetails userDetails) → ResponseEntity<FeatureFlagDto>
```

#### AdminReminderController
- Base: `/api/internal/reminder` — scheduler-callable

```
POST   /api/internal/reminder/send
  send(String token, ReminderRequest req) → ResponseEntity<Map<String,Object>>
```

#### AdminAnalyticsController
- Base: `/api/admin/analytics`

```
GET    /api/admin/analytics/usage
  usage(String entityType, String entityId, int period, AppUserDetails userDetails) → ResponseEntity<UsageMetrics>
GET    /api/admin/analytics/members
  members(String orgId, int period, AppUserDetails userDetails) → ResponseEntity<List<MemberUsage>>
GET    /api/admin/analytics/billing
  billing(String entityType, String entityId, AppUserDetails userDetails) → ResponseEntity<BillingOverview>
GET    /api/admin/analytics/cost-breakdown
  costBreakdown(String orgId, int period, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
```

#### ActivationFunnelController
- Base: `/api/admin/activation-funnel` — M-049 activation funnel reads

```
GET    /api/admin/activation-funnel
  read(int sinceDays) → ResponseEntity<FunnelReport>
```

#### MigrationAdminController
- Base: `/api/admin/migrations`

```
POST   /api/admin/migrations/reseed
  reseed() → ResponseEntity<Map<String,Object>>
```

### 1.10 SWE-bench / ProgramBench / Eval

#### SwebenchController
- Base: `/api/admin/swebench`

```
POST   /api/admin/swebench/run
  submit(SubmitRequest req) → ResponseEntity<SwebenchTaskService.TaskSubmitResult>
GET    /api/admin/swebench/preview/{instanceId}
  preview(String instanceId) → ResponseEntity<Map<String,Object>>
GET    /api/admin/swebench/instances
  listInstances() → ResponseEntity<Map<String,Object>>
```

#### ProgramBenchController
- Base: `/api/admin/programbench`

```
POST   /api/admin/programbench/run
  submit(SubmitRequest req) → ResponseEntity<ProgramBenchTaskService.TaskSubmitResult>
GET    /api/admin/programbench/preview/{taskId}
  preview(String taskId) → ResponseEntity<Map<String,String>>
```

#### EvalController
- Base: `/api/admin/eval`

```
POST   /api/admin/eval/run
  runEvaluation(List<String> providers) → ResponseEntity<?>
```

### 1.11 Enterprise (ADRs, invariants, contracts, standards, executespec folder, activity)

#### AdrController
- Base: `/api/projects/{projectId}/adrs`

```
GET    /api/projects/{projectId}/adrs
  list(String projectId, boolean acceptedOnly, Authentication auth) → ResponseEntity<List<AdrSummary>>
GET    /api/projects/{projectId}/adrs/{id}
  get(String projectId, UUID id, Authentication auth) → ResponseEntity<AdrDetail>
```

#### InvariantController
- Base: `/api/projects/{projectId}/invariants`

```
GET    /api/projects/{projectId}/invariants
  list(String projectId, Authentication auth) → ResponseEntity<List<InvariantSummary>>
GET    /api/projects/{projectId}/invariants/violations
  recentViolations(String projectId, Authentication auth) → ResponseEntity<List<ViolationDto>>
```

#### ContractConsumerController
- Base: `/api/projects/{projectId}/contracts/{contractId}/consumers`

```
GET    /api/projects/{projectId}/contracts/{contractId}/consumers
  list(String projectId, UUID contractId, Authentication auth) → ResponseEntity<List<ConsumerDto>>
POST   /api/projects/{projectId}/contracts/{contractId}/consumers
  declare(String projectId, UUID contractId, DeclareRequest body, Authentication auth) → ResponseEntity<ConsumerDto>
DELETE /api/projects/{projectId}/contracts/{contractId}/consumers/{consumerRepoId}
  remove(String projectId, UUID contractId, UUID consumerRepoId, Authentication auth) → ResponseEntity<Void>
```

#### ContractCoverageController
- Base: `/api/projects/{projectId}/repos/{repoId}/contract-coverage`

```
GET    /api/projects/{projectId}/repos/{repoId}/contract-coverage
  coverage(String projectId, UUID repoId, Authentication auth) → ResponseEntity<CoverageResponse>
POST   /api/projects/{projectId}/repos/{repoId}/contract-coverage/rescan
  rescan(String projectId, UUID repoId, Authentication auth) → ResponseEntity<CoverageResponse>
```

#### ContractDriftController
- Base: `/api/projects/{projectId}`

```
POST   /api/projects/{projectId}/contracts/{contractId}/check-drift
  checkContract(String projectId, UUID contractId, Authentication auth) → ResponseEntity<DriftDetectionService.DriftCheckResult>
POST   /api/projects/{projectId}/repos/{repoId}/check-drift
  checkRepo(String projectId, UUID repoId, Authentication auth) → ResponseEntity<List<DriftDetectionService.DriftCheckResult>>
POST   /api/projects/{projectId}/check-drift
  checkProject(String projectId, Authentication auth) → ResponseEntity<List<DriftDetectionService.DriftCheckResult>>
POST   /api/projects/{projectId}/contracts/{contractId}/refresh-from-source
  refreshFromSource(String projectId, UUID contractId, Authentication auth) → ResponseEntity<RefreshResponse>
POST   /api/projects/{projectId}/contracts/{contractId}/dismiss-drift
  dismissDrift(String projectId, UUID contractId, DismissRequest body, Authentication auth) → ResponseEntity<DismissResponse>
```

#### OrgStandardController
- Base: `/api/orgs/{orgId}/standards` — org-level coding-standard catalog

#### StandardsOverrideController
- Base: `/api/projects/{projectId}/standards`

```
POST   /api/projects/{projectId}/standards/violations/{violationId}/override
  emergencyOverride(String projectId, UUID violationId, OverrideRequest body, Authentication auth, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/projects/{projectId}/standards/{standardId}/exception
  addProjectException(String projectId, String standardId, ExceptionRequest body, Authentication auth, AppUserDetails userDetails) → ResponseEntity<Void>
DELETE /api/projects/{projectId}/standards/{standardId}/exception
  removeProjectException(String projectId, String standardId, Authentication auth, AppUserDetails userDetails) → ResponseEntity<Void>
```

#### StandardsComplianceController
- Base: `/api/projects/{projectId}/standards`

```
GET    /api/projects/{projectId}/standards/report
  report(String projectId, String orgId, int sinceDays, Authentication auth) → ResponseEntity<ComplianceReport>
```

#### ExecutespecMigrationController
- Base: `/api/admin/projects`

```
POST   /api/admin/projects/{projectId}/migrate-codemaps
  migrate(String projectId, Authentication auth) → ResponseEntity<MigrateResponse>
```

#### ActivityFeedController
- Base: `/api/projects/{projectId}/activity`

```
GET    /api/projects/{projectId}/activity
  list(String projectId, int sinceDays, List<String> eventTypes, String actorId, String targetType, String targetId, boolean includeAdminEvents, int limit, Authentication auth) → ResponseEntity<ActivityPage>
```

### 1.12 Execution (scaffolds, launches)

#### ExecutionController

```
POST   /api/projects/{projectId}/executions
  createExecution(String projectId, CreateExecutionRequest req, AppUserDetails principal) → ResponseEntity<ExecutionRunDto>
GET    /api/projects/{projectId}/executions
  listExecutions(String projectId, int page, int size, AppUserDetails principal) → ResponseEntity<Page<ExecutionRunDto>>
POST   /api/executions/{id}/analyse
  triggerAnalysis(String id, AppUserDetails principal) → ResponseEntity<Void>
PUT    /api/executions/{id}/arch-review
  approveArch(String id, ExecutionArchApprovalRequest req, AppUserDetails principal) → ResponseEntity<Void>
POST   /api/executions/{id}/plan
  generatePlan(String id, AppUserDetails principal) → ResponseEntity<Void>
PUT    /api/executions/{id}/plan/approve
  approvePlan(String id, AppUserDetails principal) → ResponseEntity<Void>
POST   /api/executions/{id}/solutions/{solutionId}/decide
  decide(String id, String solutionId, DecisionRequest req, AppUserDetails principal) → ResponseEntity<Void>
POST   /api/executions/{id}/solutions/{solutionId}/manual-ready
  manualReady(String id, String solutionId, AppUserDetails principal) → ResponseEntity<Void>
POST   /api/executions/{id}/resume
  resume(String id, AppUserDetails principal) → ResponseEntity<Void>
POST   /api/executions/{id}/abort
  abort(String id, AppUserDetails principal) → ResponseEntity<Void>
GET    /api/executions/{id}/solutions/{solutionId}/runs
  solutionRuns(String id, String solutionId, AppUserDetails principal) → ResponseEntity<List<ExecutionSolutionRun>>
GET    /api/executions/{id}/report
  getReport(String id, AppUserDetails principal) → ResponseEntity<Map<String,Object>>
GET    /api/projects/{projectId}/prs/{prNumber}/reviews
  prReviewComments(String projectId, int prNumber, String repo, AppUserDetails principal) → ResponseEntity<Map<String,String>>
GET    /api/executions/{id}/sse-token
  sseToken(String id, AppUserDetails principal) → ResponseEntity<Map<String,String>>
GET    /api/executions/{id}/events
  subscribe(String id, String token, String lastEventId, AppUserDetails principal) → SseEmitter
```

#### ScaffoldController

```
GET    /api/scaffolds
  list() → ResponseEntity<List<Scaffold>>
```

### 1.13 Migration (recipes, dry-runs)

#### MigrationController
- Base: `/api/migrations`

```
GET    /api/migrations
  list(String category, String tier, String search, AppUserDetails userDetails) → List<MigrationListItemDto>
GET    /api/migrations/{slug}
  detail(String slug, AppUserDetails userDetails) → MigrationDetailDto
POST   /api/migrations/{slug}/dry-run
  dryRun(String slug, DryRunRequest body, AppUserDetails userDetails) → MigrationDryRunDto
POST   /api/migrations/{slug}/apply
  apply(String slug, MigrationOrchestrator.ApplyRequest req, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
```

### 1.14 Readymade catalogue

#### ReadymadeController
- Base: `/api/library`

```
GET    /api/library
  list(String category, String macroGroup, String tier, String search, AppUserDetails userDetails) → List<ReadymadeListItemDto>
GET    /api/library/{slug}
  detail(String slug, AppUserDetails userDetails) → ReadymadeDetailDto
GET    /api/library/categories
  facets() → Map<String,Object>
POST   /api/library/{slug}/resolve
  resolve(String slug, String projectId, UUID repoId) → VariantResolutionDto
POST   /api/library/{slug}/instantiate
  instantiate(String slug, ReadymadeOrchestrator.InstantiateRequest req, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
POST   /api/library/favourite/{slug}
  favourite(String slug, AppUserDetails userDetails) → ResponseEntity<Void>
DELETE /api/library/favourite/{slug}
  unfavourite(String slug, AppUserDetails userDetails) → ResponseEntity<Void>
```

#### ReadymadeAdminController
- Base: `/api/admin/readymades`

```
POST   /api/admin/readymades/reseed
  reseed() → ResponseEntity<Map<String,Object>>
```

### 1.15 Integrations (Slack, Jira, Confluence webhooks)

#### SlackIntegrationController
- Base: `/api/integrations/slack`

```
GET    /api/integrations/slack/install
  getInstallUrl(AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
GET    /api/integrations/slack/oauth/callback
  oauthCallback(String code, String state) → ResponseEntity<Void>
DELETE /api/integrations/slack/disconnect
  disconnect(AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
GET    /api/integrations/slack/status
  getStatus(AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
GET    /api/integrations/slack/channels
  listSlackChannels(AppUserDetails userDetails) → ResponseEntity<List<Map<String,String>>>
GET    /api/integrations/slack/projects/{projectId}/channels
  getProjectChannels(String projectId, AppUserDetails userDetails) → ResponseEntity<List<Map<String,Object>>>
POST   /api/integrations/slack/projects/{projectId}/channels
  addProjectChannel(String projectId, Map<String,String> body, AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
DELETE /api/integrations/slack/projects/{projectId}/channels/{channelId}
  removeProjectChannel(String projectId, String channelId, AppUserDetails userDetails) → ResponseEntity<Void>
PUT    /api/integrations/slack/projects/{projectId}/channels/{channelId}/verbosity
  updateVerbosity(String projectId, String channelId, Map<String,String> body, AppUserDetails userDetails) → ResponseEntity<Void>
POST   /api/integrations/slack/test
  sendTestMessage(AppUserDetails userDetails, Map<String,String> body) → ResponseEntity<Map<String,Object>>
GET    /api/integrations/slack/logs
  getLogs(AppUserDetails userDetails, String runId) → ResponseEntity<List<SlackNotificationLog>>
```

#### IntegrationController
- Base: `/api/integrations`

```
POST   /api/integrations/{source}/webhook
  webhook(String source, Map<String,String> headers, String rawBody) → ResponseEntity<Map<String,String>>
POST   /api/integrations/jira/trigger
  jiraTrigger(Map<String,String> body, AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
POST   /api/integrations/confluence/trigger
  confluenceTrigger(Map<String,String> body, AppUserDetails userDetails) → ResponseEntity<Map<String,String>>
GET    /api/integrations/runs
  listIntegrationRuns(String source, AppUserDetails userDetails) → List<IntegrationRunSummary>
```

#### IntegrationStatusController
- Base: `/api/integrations`

```
GET    /api/integrations/status
  status(AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
GET    /api/integrations/slack/status
  slackStatus(AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
```

### 1.16 Notifications

#### NotificationController
- Base: `/api/notifications`

```
GET    /api/notifications
  list(AppUserDetails user, int page, int size) → ResponseEntity<Map<String,Object>>
GET    /api/notifications/unread-count
  unreadCount(AppUserDetails user) → ResponseEntity<Map<String,Long>>
POST   /api/notifications/{id}/read
  markRead(String id, AppUserDetails user) → ResponseEntity<Void>
POST   /api/notifications/read-all
  markAllRead(AppUserDetails user) → ResponseEntity<Void>
```

#### NotificationPrefController
- Base: `/api/notifications/preferences`

```
GET    /api/notifications/preferences
  list(AppUserDetails principal) → ResponseEntity<List<PrefRow>>
PUT    /api/notifications/preferences
  update(AppUserDetails principal, PrefUpdate body) → ResponseEntity<PrefRow>
```

### 1.17 V3 growth surfaces (referrals, entitlements, signup claim)

#### EntitlementsController
- Base: `/api/v3` — R-142 entitlements

```
GET    /api/v3/entitlements
  me(AppUserDetails userDetails) → ResponseEntity<EntitlementsResponse>
```

#### ReferralController
- Base: `/api/v3/referrals`

```
POST   /api/v3/referrals/invite
  invite(InviteRequest body, AppUserDetails userDetails) → ResponseEntity<ReferralService.InviteRecord>
GET    /api/v3/referrals/state
  state(AppUserDetails userDetails) → ResponseEntity<ReferralService.InviteState>
GET    /api/v3/referrals/lookup
  lookup(String token) → ResponseEntity<ReferralService.ReferralLookup>
```

#### SignupClaimController
- Base: `/api/v3/auth`

```
POST   /api/v3/auth/claim-anonymous-session
  claimAnonymousSession(ClaimRequest body, AppUserDetails userDetails) → ResponseEntity<ClaimResponse>
```

### 1.18 Observability + meta (health, pricing, models, feasibility, landing, contact, onboarding, SPA fallback)

#### HealthController
- Base: `/api/health`

```
GET    /api/health        health() → ResponseEntity<Map<String,Object>>
GET    /api/health/ready  ready() → ResponseEntity<Map<String,Object>>
```

#### PricingController
- Base: `/api/pricing`

```
GET    /api/pricing                              list(AppUserDetails userDetails) → ResponseEntity<List<PricingRow>>
GET    /api/pricing/{provider}/{model}           get(String provider, String model, AppUserDetails userDetails) → ResponseEntity<PricingRow>
PUT    /api/pricing/{provider}/{model}           upsert(String provider, String model, PricingUpdateRequest req, AppUserDetails userDetails) → ResponseEntity<PricingRow>
```

#### ModelController
- Base: `/api/models`

```
GET    /api/models/available  available() → ResponseEntity<AvailableModelsResponse>
```

#### MetricsController
- Base: `/api/metrics`

```
GET    /api/metrics  metrics(int days, AppUserDetails userDetails) → ResponseEntity<MetricsResponse>
```

#### FeasibilityController
- Base: `/api/feasibility`

```
POST   /api/feasibility  estimate(FeasibilityRequest req) → ResponseEntity<FeasibilityReport>
```

#### LandingController
- Base: `/api/v3`

```
GET    /api/v3/landing/stats         stats() → ResponseEntity<LandingStats>
GET    /api/v3/landing/sample-runs   sampleRuns() → ResponseEntity<List<SampleRun>>
GET    /api/v3/public/exec-snapshot  execSnapshot() → ResponseEntity<ExecSnapshot>
```

#### ContactController
- Base: `/api/contact`

```
POST   /api/contact  submitFeedback(AppUserDetails principal, ContactRequest request) → ResponseEntity<Map<String,String>>
```

#### OnboardingController
- Base: `/api/onboarding`

```
GET    /api/onboarding/sample-specs    listSamples() → ResponseEntity<List<SampleSpecDto>>
POST   /api/onboarding/pick-sample     pickSample(AppUserDetails userDetails, PickSampleRequest req) → ResponseEntity<PickSampleResponse>
GET    /api/onboarding/sandbox-status  sandboxStatus(AppUserDetails userDetails) → ResponseEntity<Map<String,Object>>
```

#### SpaFallbackController
- SPA history-API routing — all non-asset paths return `index.html`

```
GET    /favicon.ico                                                              → ResponseEntity<Void>
GET    /                                                                         → ResponseEntity<Resource>
GET    /{path:[^\.]*}                                                            → ResponseEntity<Resource>  (1-segment)
GET    /{a:[^\.]*}/{path:[^\.]*}                                                 → ResponseEntity<Resource>  (2-segment)
GET    /{a:[^\.]*}/{b:[^\.]*}/{path:[^\.]*}                                      → ResponseEntity<Resource>  (3-segment)
GET    /{a:[^\.]*}/{b:[^\.]*}/{c:[^\.]*}/{path:[^\.]*}                           → ResponseEntity<Resource>  (4-segment)
GET    /{a:[^\.]*}/{b:[^\.]*}/{c:[^\.]*}/{d:[^\.]*}/{path:[^\.]*}                → ResponseEntity<Resource>  (5-segment)
```

### 1.19 Test endpoints (dev-only smoke probes — `/api/v2/test/*`)

- **ContextStoreTestController** — `/api/v2/test` (no public mappings)
- **ExecutorTestController** — `GET /api/v2/test/executor` → `testExecutor(provider, model, spec, classification)`
- **MetricsTestController** — `GET /api/v2/test/metrics` → `dumpV2Metrics()`
- **NativeToolsTestController** — `GET /api/v2/test/native-tools[/all]` → `testNativeTools(...)` / `testAll()`
- **PlannerTestController** — `GET /api/v2/test/planner` → `testPlanner(provider, model, spec, classification)`
- **ReviewerTestController** — `GET /api/v2/test/reviewer` → `testReviewer(provider, model)`
- **RouterTestController** — `/api/v2/test` (no public mappings)
- **ToolRegistryTestController** — `/api/v2/test` (no public mappings)
- **WorkerTestController** — `GET /api/v2/test/worker` → `testWorker(provider, model)`

---

## 2. Services (key public APIs)

Only the orchestration-level services that controllers call directly are enumerated here. Helper services (e.g. `*Pdf*`, `*Audit*` variants, format adapters) are intentionally omitted.

### 2.1 TierEnforcementService
- Pkg: `auth.service` — enforces monthly run quotas + per-tier ceilings.

```java
void   assertAndIncrementRunCount(String userId)
void   assertCanSubmitRun(String userId)
void   assertCanApplySuggestion(String userId, PipelineRun run)
void   assertCanCreateProject(String userId, String orgId)
void   assertCanLinkRepoToProject(String userId, String projectId)
void   assertComplexityAllowed(String userId, TaskPlan plan)
void   incrementRunCount(String userId)
void   decrementRunCount(String userId)
int    effectiveOrgLimit(Org org)
int    effectiveUserLimit(AppUser user)
boolean shouldResetUserCounterForJob(AppUser user, Map<String, BillingSubscription> activeByUser)
boolean shouldResetOrgCounterForJob(Org org, Map<String, BillingSubscription> activeByUser)
String resolveUserTier(String userId)
boolean requiresCostCap(String tier, boolean hasByok)
double resolveRunCostBudget(String tier, boolean hasByok)
```

### 2.2 BillingService
- Pkg: `billing.service` — gateway-routed (Razorpay / Stripe).

```java
List<PlanResponse>       listPlans(String keyMode)
SubscriptionInitResult   subscribe(String userId, UUID planId)
BillingStatusResponse    verifyAndActivate(String userId, VerifyPaymentRequest request)
void                     cancel(String userId)
BillingStatusResponse    updateSeats(String userId, int newTotalSeats)
BillingStatusResponse    getStatus(String userId)
void                     processRazorpayWebhook(String rawPayload, String signature)
void                     processStripeWebhook(String rawPayload, String signature, String eventType)
```

### 2.3 ProjectArchitectureService
- Pkg: `codeintel` — 3-step pipeline owner (codemap → per-repo HLD/LLD → cross-repo synthesis).

```java
ProjectArchitectureDoc       generate(String projectId)
Optional<RepoArchitectureDoc> latestRepoArch(String projectId, UUID repoId)
RepoArchitectureDoc          regenerateForRepo(String projectId, UUID repoId, String patOverride)
Optional<ProjectArchitectureDoc> latest(String projectId)
List<ProjectArchitectureDoc> versions(String projectId)
ProjectArchitectureDoc       annotate(String projectId, String annotation)
Optional<ProjectCodeMap>     latestCodeMap(String projectId, UUID repoId)
```

### 2.4 ProjectInviteService
- Pkg: `sharing.service` — V175 cross-org project sharing.

```java
ProjectInvite      createInvite(String projectId, String email, String role, AppUser inviter)
String             accept(String token, AppUser caller)
void               revoke(UUID inviteId, AppUser actor)
List<ProjectInvite> listPending(String projectId)
Optional<ProjectInvite> findByToken(String token)
```

### 2.5 ProjectShareLinkService
- Pkg: `sharing.service` — V175 public read-only share links.

```java
ProjectShareLink   createLink(String projectId, ProjectShareLink.Surface surface, String scopeId, String label, Instant expiresAt, AppUser creator)
void               revoke(UUID linkId, AppUser actor)
ProjectShareLink   resolveActive(String token)
void               recordView(ProjectShareLink link)
List<ProjectShareLink> listForProject(String projectId)
long               activeCountForProject(String projectId)
```

### 2.6 ScmPushService
- Pkg: `integration.scm` — multi-repo push + per-repo PR + cross-link.

```java
void                  push(PipelineRun run)
ApplyPushResult       pushApplyChanges(PipelineRun run, String commitMessage)
ApplyPushResult       skipped(String reason)
ApplyPushResult       multiRepo(List<ApplyRepoOutcome> outcomes)
ApplyPushResult       singleRepo(ApplyRepoOutcome outcome)
record ApplyRepoOutcome(String repoFullName, String branch, boolean ok, boolean clean, String message)
record ApplyPushResult(String mode, String skippedReason, List<ApplyRepoOutcome> perRepo)
```

### 2.7 RunOrchestrator
- Pkg: `integration` — V2 pipeline orchestrator.

```java
PipelineRun     submitRun(String userRequest, String source, String repoUrl, String repoMode, String githubPat,
                          String githubOwner, boolean skipApproval, boolean autoMerge, String modelName,
                          String aiProvider, String createdByUserId, String orgId, String projectId,
                          String executionMode, List<UUID> selectedRepoIds, String figmaUrl, boolean tddMode,
                          String branch, String prReviewComments)
boolean         cancel(String runId)
boolean         pause(String runId)
ResumeOutcome   resume(String runId)
CrashResumeOutcome resumeAfterCrash(String runId)
RunPipelineRequest fromPausedRow(PipelineRun run)
List<String>    haltAllActiveRuns(String reason)
Map<String,Object> forceFailTask(String runId, String taskId, String reason)
void            retryTask(String runId, String taskId)
void            retryValidation(String runId)
void            reReview(String runId)
record RunPipelineRequest(String runId, String userRequest, String provider, String model,
                          String executionMode, boolean skipApproval, String projectId,
                          List<UUID> selectedRepoIds, String githubPat, boolean tddMode, ...)
```

### 2.8 SequentialExecutor
- Pkg: `harness` — per-task tier-routed worker dispatcher (the V2 executor boundary, not a service per se).

```java
ExecutionResult  execute(ExecutionRequest req)
void             persistTaskStatus(String runId, String taskIdShort, String status)
record ResumeRequest(String resumeFromTaskId, ...)
```

### 2.9 ValidationReportService
- Pkg: `report`

```java
Optional<ValidationReport>       generateAndPersist(String runId, ReportInput input)
Optional<ValidationReportDTO>    getByRunId(String runId)
Optional<ValidationReport>       findEntityByRunId(String runId)
Optional<ValidationReportShareDTO> getByShareToken(String shareToken)
String                           share(String runId)
String                           extractFailureSignature(String failureMessage)
```

### 2.10 MailService
- Pkg: `auth.service` — transactional email (SMTP or Gmail API). Twenty-three send methods grouped here:

```java
String getAdminAddress()
void sendVerificationEmail(String toEmail, String firstName, String token)
void sendPasswordResetEmail(String toEmail, String firstName, String token)
void sendTemporaryPasswordEmail(String toEmail, String firstName, String tempPassword)
void sendOrgInviteEmail(String toEmail, String orgName, String token)
void sendProjectInviteEmail(String toEmail, String projectName, String inviterEmail, String acceptUrl, long expiresInDays)
void sendReferralInvite(String toEmail, String referrerName, String token)
void sendEscalationToAdmin(String adminEmail, String invitedEmail, String orgName, String invitationId)
void sendInviteRejectedEmail(String orgAdminEmail, String invitedEmail)
void sendNewUserAlertToAdmin(String adminEmail, String newUserEmail)
void sendAdminReminderEmail(String to, String subject, String body)
void sendWeeklyAdminSummary(String adminEmail, Map<String, Object> summary)
void sendContactFeedbackEmail(String adminEmail, String userEmail, String userName, String subject, String feedbackBody)
void sendWelcomeEmail(String toEmail, String firstName, String tier)
void sendTierUpgradeEmail(String toEmail, String firstName, String planName, String tier)
void sendSuspensionEmail(String toEmail, String firstName)
void sendRunCompletionEmail(String toEmail, String firstName, String runId, String status, String statusReason, String runTitle, Double verifiedScore, Double costUsd, String prUrl, String runUrl)
void sendBonusRunsEmail(String toEmail, String firstName, int quantity)
void sendInvoiceEmail(String toEmail, String firstName, String invoiceNumber, long subtotalPaise, long gstPaise, long totalAmountPaise, byte[] pdfBytes, String pdfFilename)
void sendDunningReminder(String toEmail, String firstName, String planName, String urgency)
void sendRenewalReminder(String toEmail, String firstName, String planName, String renewalDate)
void sendUserUsageReport(String toEmail, String firstName, String periodLabel, String periodRange, UsageReportService.UserUsageData data)
void sendOrgUsageReport(String toEmail, String orgName, String periodLabel, String periodRange, UsageReportService.OrgUsageData data)
```

### 2.11 FeatureFlagService
- Pkg: `config.featureflag` — Redis-backed flag store with pub/sub invalidation.

```java
boolean         isEnabled(String key)
String          getString(String key, String defaultValue)
int             getInt(String key, int defaultValue)
FeatureFlag     update(String key, String value, String userId)
List<FeatureFlag> listAll()
void            onMessage(Message message, byte[] pattern)
```

### 2.12 AuthService

```java
UserDto         register(RegisterRequest req)
void            verifyEmail(String token)
LoginResponse   verifyEmailAndAuthenticate(String token)
LoginResponse   login(LoginRequest req)
String          generateRefreshToken(String userId)
LoginResponse   refresh(String refreshToken)
void            logout(String refreshToken)
void            forgotPassword(String email)
void            resetPassword(String token, String newPassword)
void            changePassword(String userId, ChangePasswordRequest req)
void            resendVerification(String email)
AppUser         findOrCreateFromOAuth(String provider, String oauthId, String email, String name, String avatarUrl)
```

### 2.13 OrgService

```java
OrgDto              createOrg(String creatorUserId, CreateOrgRequest req)
OrgDto              getOrg(String orgId)
OrgDto              updateOrg(String orgId, String actorId, String name, List<String> domains)
List<OrgMemberDto>  listMembers(String orgId)
void                removeMember(String orgId, String userId, String performedBy)
void                changeMemberRole(String orgId, String userId, String newRole, String actorId)
List<OrgDto>        listAllOrgs()
void                changeOrgStatus(String orgId, String status, String performedBy)
void                suspendMember(String orgId, String targetUserId, boolean suspend, String performedBy)
Org                 createOrgAsAdmin(String name, String adminUserId)
Org                 bootstrapPersonalOrg(String userId)
OrgDto              updateRunLimitOverride(String orgId, Integer limit, String actorId)
OrgDto              updateExternalSharing(String orgId, boolean enabled, String actorId)            // V198
OrgDto              updateApiKeysProvided(String orgId, boolean provided, String actorId)
OrgDto              updateAiKeySettings(String orgId, String anthropicKey, String openAiKey, String geminiKey, String actorId)
OrgDto              updateKeySourceSettings(String orgId, String keySourceMode, List<String> enabledProviders, String actorId)
OrgDto              updateJiraSettings(String orgId, String baseUrl, String email, String apiToken, String actorId)
OrgDto              updateConfluenceSettings(String orgId, String baseUrl, String email, String apiToken, String actorId)
List<String>        getEnabledIntegrations(String orgId)
void                changeOrgTier(String orgId, String newTier, String performedBy)
SeatsAdjustResponse adjustSeats(String orgId, int newSeats, String performedBy)
```

### 2.14 ProjectService

```java
ProjectDto              createProject(String orgId, String createdBy, CreateProjectRequest req)
List<ProjectDto>        listProjectsForUser(String orgId, String userId, boolean isOrgAdmin)
ProjectDto              getProject(String projectId, String userId, boolean isSuperAdmin, boolean isOrgAdmin)
ProjectDto              getProjectIncludingDeleted(String projectId)
ProjectDto              archiveProject(String projectId, String actorId)
ProjectDto              unarchiveProject(String projectId, String actorId)
void                    softDeleteProject(String projectId, String actorId)
ProjectDto              restoreProject(String projectId, String actorId)
void                    addMember(String projectId, String userId, String role, String actorId)
void                    removeMember(String projectId, String userId, String actorId)
ProjectDto              updateSettings(String projectId, UpdateProjectSettingsRequest req, String actorId)
List<ProjectMemberDto>  listMembers(String projectId)
```

### 2.15 InvitationService

```java
OrgInvitation       createInvitation(String orgId, String invitedByUserId, InviteRequest req)
void                approveEscalation(String invitationId, String adminUserId)
void                rejectEscalation(String invitationId, String adminUserId)
void                acceptInvitation(String token, String currentUserId)
OrgInvitation       createAdminInvitation(String orgId, String invitedEmail, String orgRole, String adminUserId)
void                resendInvitation(String invitationId, String actorUserId)
List<OrgInvitation> listEscalated()
List<OrgInvitation> listByOrg(String orgId)
```

### 2.16 GitHubRepoService
- Pkg: `repo` — manages GitHub repos for runs.

```java
String              createRepo(String goal, String nameOverride, String visibility)
boolean             closePullRequest(String repoFullName, int prNumber, String overridePat)
boolean             deleteBranch(String repoFullName, String branch, String overridePat)
List<ChangedFile>   compareCommits(String repoFullName, String baseSha, String headSha, String overridePat)
boolean             installWebhook(String repoFullName, String overridePat)
void                updateRepoDescription(String repoUrl, String description, String overridePat)
List<String>        listRepoFiles(String repoUrl, String overridePat)
String              readRepoFile(String repoUrl, String relativePath, String overridePat)
void                cloneRepo(String repoUrl, String workspacePath)
String              generateRepoName()
String              generateSlug(String goal)
String              getDefaultBranch(String repoUrl, String overridePat)
long                getRepoSizeKb(String repoUrl, String overridePat)
String              getBranchHeadSha(String repoUrl, String branch, String overridePat)
String              createPullRequest(String repoUrl, String head, String base, String title, String prBody, String overridePat)
void                updatePullRequestBody(String repoUrl, int prNumber, String newBody, String overridePat)
void                requestReviewers(String repoUrl, int prNumber, List<String> reviewers, String overridePat)
String              fetchPrReviewComments(String repoFullName, int prNumber, String overridePat)
String[]            extractOwnerRepo(String repoUrl)
PullRequestSnapshot getPullRequestSnapshot(String repoFullName, int prNumber, String overridePat, int diffMaxChars)
CheckRunRollup      getCheckRunRollup(String repoFullName, String sha, String overridePat)
List<RemoteRepo>    listOwnerRepos(String ownerFilter, String overridePat)
record ChangedFile(String filename, String status, int additions, int deletions)
record RemoteRepo(String fullName, String defaultBranch, boolean isPrivate)
record PullRequestSnapshot(String title, String body, String state, boolean merged, int additions, int deletions, int changedFiles, String headBranch, String baseBranch, String headSha, String diffPreview)
record CheckRunRollup(String status, int failingCount)
```

### 2.17 DeploymentService

```java
void        validatePrerequisites()
boolean     isEnabled()
Deployment  deploy(String runId, RunRepo.RepoMode repoMode, String repoUrl, String repoName)
void        rollback(String deploymentId)
void        deleteDeployment(String deploymentId)
```

### 2.18 PreviewService
- Pkg: `deployment.preview` — Cloud Run or Docker preview, 5-min TTL.

```java
PreviewAnalysis analyze(String runId)
PreviewSession  start(String runId, AppUser user, Map<String, String> envVars)
void            stop(String runId)
PreviewSession  rebuild(String runId, AppUser user)
PreviewSession  getStatus(String runId)
String          getLogs(String runId, int tailLines, String filter)
ExecResult      execInContainer(String runId, String cmd, List<String> args)
record PreviewAnalysis(DatabaseType databaseType, List<EnvVarDetector.DetectedEnvVar> requiredEnvVars)
```

### 2.19 RunCostTracker
- Pkg: `cost` — per-run cost accumulator (cache-aware pricing, 3-provider).

```java
void      record(String runId, String provider, String model, ChatResponse response)
Snapshot  snapshot(String runId)
Snapshot  snapshotTask(String runId, String taskId)
Snapshot  finalizeRun(String runId)
Estimate  estimateRun(String provider, String model, List<String> taskComplexities)
Estimate  estimateRunTierAware(String provider, List<String> taskComplexities)
double    computeCost(String provider, String model, int inTok, int outTok)
record Estimate(double lowUsd, double highUsd, int credits, double hardCapUsd, int taskCount)
record Snapshot(int inputTokens, int outputTokens, int cacheWriteTokens, int cacheReadTokens, double costUsd, int calls)
```

### 2.20 ModelRegistry
- Pkg: `router` — YAML-backed model metadata lookup.

```java
String   canonicalize(String modelName)
String   providerFor(String modelName)
boolean  isKnownModel(String name)
int      contextLimitFor(String modelName)
int      contextLimitForOrPrefixFallback(String modelName)
int      maxOutputForModel(String modelName)
double[] pricingFor(String modelName)
String   notesFor(String modelName)
String   displayLabelFor(String modelName)
List<String[]> fallbackChainFor(String modelName)
List<String[]> fallbackChainForTier(String tier)
String   freeTierModel()
String   freeTierProvider()
String   workhouseModelFor(String provider)
String   planningModelFor(String provider)
String   lightweightModelFor(String provider)
Map<String, ModelMatrixProperties.ModelConfig> allModels()
```

### 2.21 StateStore
- Pkg: `state` — wide JPA façade over the run-lifecycle entities. ~100 methods; abbreviated below.

```java
PipelineRun  createRun(String request, String plannerModel, String workerModel, String reviewerModel)
PipelineRun  save(PipelineRun run)
Optional<PipelineRun> findRun(String runId)
void         savePlan(String runId, TaskPlan plan)
void         markApproved(String runId)
void         savePlanVersion(String runId, String source, String createdBy)
void         incrementPlanVersion(String runId)
void         recreateTasksFromPlan(String runId, TaskPlan plan)
void         markRejected(String runId, String reason)
void         markReviewing(String runId)
void         saveReview(String runId, TaskReviewReport review)
void         saveReviewV2(String runId, ReviewReport review)
ReviewReport getReviewReportV2(String runId)
void         markComplete(String runId)
void         markFailed(String runId, String reason)
void         markPartial(String runId, String reason)
void         markApiSpecReady(String runId)
void         markPreviewReady(String runId, String serveDir)
void         markRetrying(String runId, String taskId)
void         markRetryingWithFeedback(String runId, String taskId, String reworkFeedback)
boolean      lockTask(String taskId, String model, String branchName)
void         completeTask(String taskId, WorkerReport report)
void         addTokensToRun(String runId, int inputTok, int outputTok)
void         setAgentTokens(String runId, String agentRole, int inputTok, int outputTok, String provider, String model)
void         setCostEstimate(String runId, double lowUsd, double highUsd)
void         setUiStyle(String runId, String uiStyleJson)
void         setCreditsCharged(String runId, int credits)
void         setHardCapUsd(String runId, double hardCap)
void         setUserCostCap(String runId, Double userCostCap)
void         setBatchCostFields(String runId, Integer batchCredits, boolean batchAvailable, int creditSavings)
void         setBatchInfo(String runId, String batchId, String batchProvider)
void         setApprovedExecutionMode(String runId, String mode)
void         clearCurrentGate(String runId)
void         updateBatchProgress(String runId, int completed, int total)
List<PipelineRun>   findRunsByStatusIn(List<String> statuses)
void         markTaskComplete(String taskId)
void         setTaskModelProvider(String taskId, String model, String provider)
void         markTaskFailed(String taskId, String reason)
void         setPromptHash(String taskId, String promptHash)
void         resetTaskForRetry(String taskId)
void         updateTaskTokens(String taskId, int inputTokens, int outputTokens)
void         setActualCost(String runId, double costUsd)
void         updateTaskReadmeStatus(String taskId, boolean readmeUpdated)
void         recordCompaction(String taskId, String checkpointPath)
void         saveTask(AgentTask task)
List<AgentTask>  getPendingTasks(String runId)
List<AgentTask>  getIncompleteTasks(String runId)
List<AgentTask>  getAllTasks(String runId)
String       recordGateOpened(String runId, String gateType)
void         recordGateResolved(String gateEventId, boolean approved)
Optional<PipelineRun> findResumableRun()
Page<PipelineRun>     listRuns(String status, Pageable pageable)
PipelineRun  archiveRun(String runId)
PipelineRun  unarchiveRun(String runId)
PipelineRun  softDeleteRun(String runId)
PipelineRun  restoreRun(String runId)
boolean      cancelRun(String runId)
boolean      pauseRun(String runId)
boolean      resumeRun(String runId)
MetricsData       computeMetrics()
MetricsData       computeMetricsForOrg(String orgId)
TimelineData      buildTimeline(String runId)
ModelComparisonData computeModelComparison(int days)
ModelComparisonData computeModelComparisonForOrg(int days, String orgId)
UserRunsSummaryData computeUserRunsSummary(String orgId, int days)
UserRunsSummaryData computeUserRunsSummaryGlobal(int days)
void         logMessage(String runId, String from, String to, String type, String summary, int tokenCost)
List<PipelineRun>  findRunsByStatuses(List<String> statuses)
List<AgentTask>    getActiveTasksForRun(String runId)
List<AgentTask>    getWorkingTasks()
List<PipelineRun>  getOutageQueuedRuns()
void         updateLastActivity(String taskId)
void         markStuckDetected(String taskId)
void         markRunStuck(String runId)
void         markTaskForRetry(String taskId)
void         blockTask(String taskId, String reason)
List<String> getTaskEventLog(String taskId, int limit)
void         appendTaskLog(String taskId, String event)
AgentTask    getTask(String taskId)
void         failTask(String taskId, String reason)
List<AgentTask>  createSubtasks(String parentTaskId, String runId, List<TaskDefinition> subtaskDefs, String tenantId)
List<AgentTask>  getSubtasks(String parentTaskId)
void         completeParentFromSubtasks(String parentTaskId)
List<AgentTask>  getTasksForRun(String runId)
void         escalateTask(String taskId, String provider, String model)
void         setEscalationLevel(String taskId, int level)
void         forceCompactionFlag(String taskId)
void         recordLlmFailure(String taskId, Object failure)
List<PipelineRun>  getRecentCompletedRuns(int limit)
long         countCompletedRuns()
TaskReviewReport   getReviewReport(String runId)
void         saveChatMessage(String runId, String role, String content)
List<Map<String,String>>  getChatHistory(String runId)
PipelineRun  createIterationRun(PipelineRun sourceRun, String newRequest)
List<AgentTask>   appendSuggestionTasks(String runId, TaskPlan miniPlan, int applyIndex, ApplySource source)
void         incrementApplySuggestionCount(String runId)
List<PipelineRun>  findRunsToArchive(Instant cutoff)
void         markGcsArchived(String runId, String gcsKey)
List<PipelineRun>  findRunsToCleanup(Instant cutoff)
List<PipelineRun>  findTerminalRunsWithoutGcsKey(Instant cutoff)
void         updateStatus(String runId, String status)
void         updateHeartbeat(String runId, String instanceId, Instant heartbeat)
```

### 2.22 LlmRouter (V2)
- Pkg: `agent` — native-tool-only, provider-agnostic dispatcher.

```java
ChatResponse  call(LlmRequest request)
```

### 2.23 FallbackLlmRouter (legacy)
- Pkg: `agent` — fallback router for non-tool-calling agents (`AccuracyAssessmentService`, `ConversationAgent`, `testgen/*`).

```java
LlmResponse  callWithFallbackTracked(String primaryProvider, String primaryModel, Prompt prompt, String runId, String agentId, FallbackMode mode, int iteration, boolean justCompacted)
ChatResponse callWithFallback(String primaryProvider, String primaryModel, Prompt prompt, String runId, String agentId, FallbackMode mode, int iteration, boolean justCompacted)
LlmResponse  callDirect(String provider, String model, Prompt prompt)
void         clearRunCallCounter(String runId)
int          getRunCallCount(String runId)
void         setRunUserTier(String runId, String userTier)
String       getRunUserTier(String runId)
record LlmResponse(ChatResponse response, String provider, String model)
```

### 2.24 ToolRegistry
- Pkg: `tool` — adapts `Tool` beans to Spring AI `ToolCallback`s.

```java
List<String>          toolNames()
Tool                  find(String name)
List<ToolCallback>    bind(ExecutionContext ctx)
List<ToolCallback>    bindForWorker(ExecutionContext ctx)     // filters gitCommit per CLAUDE.md
Map<String, ToolDefinition> toolDefinitions()
ToolDefinition        getToolDefinition()
String                call(String toolInput)
```

### 2.25 ContextStore
- Pkg: `context` — Redis-backed worker-context snapshot store (24h TTL).

```java
String                   keyFor(String runId, String taskId)
int                      save(ExecutionContext ctx, List<Message> history, int iteration)
Optional<SavedContext>   load(ExecutionContext ctx)
void                     delete(ExecutionContext ctx)
boolean                  exists(ExecutionContext ctx)
```

### 2.26 FindingPatchSubmitter
- Pkg: `finding` — single chokepoint for the four customer-facing autofix surfaces.

```java
SubmitResult submit(Finding finding)
record SubmitResult(Outcome outcome, String runId, String findingKey)
```

---

## 3. Agents

Tier resolved from `application.yml model-matrix.agent-tier-map`. PLANNING → Sonnet 4.6 / GPT-5.4 / Gemini 3 Pro; WORKHORSE → Sonnet 4.6 / GPT-5.4-mini / Gemini 3 Flash; LIGHTWEIGHT → Haiku 4.5 / GPT-5.4-mini / Gemini 3 Flash.

| Class | Pkg | Tier | Purpose |
| --- | --- | --- | --- |
| `SmartClassifierAgent` | `agent` | LIGHTWEIGHT | Phase 0 of the hierarchical planner — chooses the planning strategy for a run before any tier work runs. |
| `OutlinePlannerAgent` | `agent` | PLANNING | Phase 1 — produces a `Plan` for the run. For LARGE/LEGACY emits SKELETON_ONLY tasks. |
| `TaskExpanderAgent` | `agent` | WORKHORSE | Phase 2 — fills in the body of one skeleton task (description / acceptance / files / tier) in parallel. |
| `CoherencePlannerAgent` | `agent` | LIGHTWEIGHT | Phase 3 — final coherence check on the assembled plan after parallel expansion. |
| `WorkerAgent` | `agent` | WORKHORSE | V2 sequential worker agent — per-task tool-calling loop with iter / token caps. |
| `PatchAgent` | `agent` | WORKHORSE | Patch-mode entry point — single thin-agent loop for bug-fix shapes (SWE-bench / autofix surfaces). |
| `ConversationAgent` | `agent` | LIGHTWEIGHT | Post-run conversational AI agent (uses `FallbackLlmRouter`). |
| `ReviewerAgent` | `reviewer` | PLANNING | V2 post-execution code reviewer — general + wiring passes. |
| `PrReviewAgent` | `prreview` | PLANNING | PR Review Agent — senior-engineer-style review against a PR diff, emits structured comments. |
| `CodeReviewAgent` | `codereview` | PLANNING | LLM-augmented whole-repo review (PLANNING tier for quality). |
| `ApiTestSuiteAgent` | `apitests` | WORKHORSE | Generates OpenAPI 3.0 YAML spec for backend-only runs, post-merge. |
| `TestPlannerAgent` | `testgen` | WORKHORSE | Plans integration tests from the spec (NOT from generated code). Always CAPABLE tier. |
| `TestWorkerAgent` | `testgen` | WORKHORSE | Generates a single integration test class per `TestTask`. Always CAPABLE tier. |
| `ContextAgent` | `context` | LIGHTWEIGHT | Compresses worker context near token-budget boundaries. |
| `RunLedgerAgent` | `memory` | LIGHTWEIGHT | Generates a compressed ledger entry per completed run; appended to `project_run_ledger`. |
| `PromptOptimiserAgent` | `optimiser` | WORKHORSE | Self-improvement pass over prompt files; audits prompt drift. |
| `PreviewAgent` | `deployment` | n/a (deterministic) | Generates `Dockerfile`, `docker-compose.yml`, and other deployment artefacts. Not LLM-backed. |

Additional non-`*Agent`-named agents in the tier map (treated as agents by `application.yml`):

| Class | Pkg | Tier | Purpose |
| --- | --- | --- | --- |
| `RepoArchitectureGenerator` | `codeintel` | PLANNING | Per-repo HLD + LLD from codemap. |
| `ArchitectureDocGenerator` | `codeintel` | PLANNING | Cross-repo synthesis from per-repo arch docs. |
| `ProjectDocGeneratorService` | `codeintel` | WORKHORSE | EXPLANATION / SETUP docs. |
| `ProjectChatCompiler` | `memory` | PLANNING | Compiles chat threads into a polished spec via `ProjectChatService.compile`. |
| `ProjectChatCompactor` | `memory` | LIGHTWEIGHT | Chat-history compaction (older N → summary, keep last 6 verbatim). |
| `ProjectBriefService` | `codeintel` | LIGHTWEIGHT | Per-project micro-summary surfaced on dashboard. |
| `RunTitleSummarizer` | `state` | LIGHTWEIGHT | Generates short run titles for the runs list. |
| `ValidationReportGenerator` | `report` | WORKHORSE | LLM-judged validation report (legacy fallback for pre-V106 rows). |
| `TestReportGenerator` | `testgen` | LIGHTWEIGHT | Per-run test-result summary. |

---

## 4. Tools

V2 worker tools — all bound through `ToolRegistry.bindForWorker(ctx)` (which filters out `gitCommit`; auto-push happens at run-end via `ScmPushService.pushMultiRepo`).

| Class | `name()` | Purpose |
| --- | --- | --- |
| `ReadFileTool` | `readFile` | Reads file contents with optional line-range slicing (8k char truncation). |
| `WriteFileTool` | `writeFile` | Creates a NEW file. Errors if the file already exists. |
| `EditFileTool` | `editFile` | Multi-edit string-replacement on an existing file. |
| `ListFilesTool` | `listFiles` | Lists directory contents with optional recursion. |
| `SearchFilesTool` | `searchFiles` | Native (no shell subprocess) recursive content search. |
| `ShellTool` | `shell` | Executes a sandboxed shell command in the run's workspace (6k char truncation). |
| `GitStatusTool` | `gitStatus` | Returns `git status --short` output for the run's workspace. |
| `GitDiffTool` | `gitDiff` | Returns working-tree diff (unstaged + staged) since HEAD (5k char truncation). |
| `GitCommitTool` | `gitCommit` | Stages all changes and commits with the supplied message. (Filtered from worker authority.) |
| `BuildProjectTool` | `buildProject` | Runs the project's detected build command and returns pass/fail + output. |
| `RunTestsTool` | `runTests` | Structured pytest tool for patch-mode (SWE-bench / generic Python). Patch-mode only. |
| `CompleteTaskTool` | `completeTask` | Structured signal that a task is finished (native termination tool). |

Supporting infrastructure under `tool/` (not LLM-callable):

- `Tool` — abstract tool interface (`name()`, `description()`, `schema()`, `invoke(ExecutionContext)`).
- `ToolResult`, `GitToolResult` — structured result envelopes.
- `ExecutionContext` — per-task tool dispatch context (workspace dir, runId, taskId, repos, sandbox policy).
- `WorkspaceContext` — thread-local file/git redirection so parallel subtasks each see their own isolated dir.
- `WorkspaceContextThreadLocalAccessor` — bridges `WorkspaceContext` into Micrometer's context propagation.
- `PathSafety` — path-escape guard shared by all V2 tools that touch the filesystem.
- `FileSandboxPolicy` — guardrail-aware file-sandbox policy.
- `ShellSandboxPolicy` — guardrail-aware shell-sandbox policy.
- `FileLockTool`, `FileSystemTool`, `GitTool`, `GitInvoker`, `MavenDependencyValidator` — internal helpers.
- `ToolRegistry` — central registry, adapts beans to Spring AI `ToolCallback`s.

---

## 5. Repositories

Spring Data JPA repositories. `manages` is the entity, `@Query` count is the number of `@Query`-annotated methods (custom JPQL/native queries are listed below each repo when non-trivial).

### activity/
- **ActivityFeedEntryRepository** — manages `ActivityFeedEntry` — @Query: 2
  - `SELECT a FROM ActivityFeedEntry a WHERE a.projectId = :projectId AND a.occurredAt >= :from AND a.occurredAt < :to ORDER BY a.occurredAt DESC`

### adr/
- **AdrRepository** — manages `Adr` — @Query: 1
  - `SELECT a FROM Adr a WHERE a.projectId = :projectId AND a.status = 'ACCEPTED' ORDER BY a.adrIdInRepo`

### agent/
- **PromptHashAuditRepository** — manages `PromptAudit` — @Query: 0

### analytics/
- **UserActivationEventRepository** — manages `UserActivationEvent` — @Query: 2
  - `SELECT COUNT(e) FROM UserActivationEvent e WHERE e.eventName = :event`
  - `SELECT COUNT(e) FROM UserActivationEvent e WHERE e.eventName = :event AND e.occurredAt >= :since`

### arch/
- **ArchInvariantRepository** — manages `ArchInvariant` — @Query: 0
- **ArchInvariantViolationRepository** — manages `ArchInvariantViolation` — @Query: 1
  - `SELECT v FROM ArchInvariantViolation v WHERE v.invariantId IN (SELECT i.id FROM ArchInvariant i WHERE i.projectId = :projectId) ORDER BY v.detectedAt DESC`

### auth/
- **ActivityAuditRepository** — manages `ActivityAudit` — @Query: 2 (chain-ordered fetches for tamper detection)
- **AppUserRepository** — manages `AppUser` — @Query: 4
  - `SELECT u FROM AppUser u WHERE u.id = :id`
  - `SELECT u FROM AppUser u WHERE u.status != 'DELETED' ORDER BY u.createdAt DESC`
  - `SELECT u FROM AppUser u WHERE u.orgId = :orgId AND u.status = 'ACTIVE'`
- **EmailTokenRepository** — manages `EmailToken` — @Query: 1 (`DELETE FROM EmailToken t WHERE t.expiresAt < :now`)
- **OrgInvitationRepository** — manages `OrgInvitation` — @Query: 0
- **OrgMemberRepository** — manages `OrgMember` — @Query: 0
- **OrgRepository** — manages `Org` — @Query: 2 (list non-deleted, find-by-id)
- **PaymentAuditRepository** — manages `PaymentAudit` — @Query: 1 (user+org scoped pagination)
- **ProjectMemberRepository** — manages `ProjectMember` — @Query: 1 (`SELECT pm.projectId FROM ProjectMember pm WHERE pm.userId = :userId`)
- **ProjectRepository** — manages `Project` — @Query: 0
- **RefreshTokenBlocklistRepository** — manages `RefreshTokenBlocklist` — @Query: 2 (native upsert + GC)
- **TierConfigRepository** — manages `TierConfig` — @Query: 0

### billing/
- **RunCostLogRepository** — manages `RunCostLog` — @Query: 0
- **AccountingEntryRepository** — manages `AccountingEntry` — @Query: 2 (account/entryType totals over date range)
- **BillingPlanLimitRepository** — manages `BillingPlanLimit` — @Query: 0
- **BillingPlanRepository** — manages `BillingPlan` — @Query: 0
- **BillingProfileRepository** — manages `BillingProfile` — @Query: 0
- **BillingSubscriptionRepository** — manages `BillingSubscription` — @Query: 7 (active-subscription lookups by user / status / customerId)
- **BillingUsageRepository** — manages `BillingUsage` — @Query: 3 (current-period reads, increment)
- **BillingWebhookEventRepository** — manages `BillingWebhookEvent` — @Query: 0
- **FounderPricingAllocationRepository** — manages `FounderPricingAllocation` — @Query: 0
- **FounderPricingDiscountRepository** — manages `FounderPricingDiscount` — @Query: 1
- **InvoiceLineItemRepository** — manages `InvoiceLineItem` — @Query: 0
- **InvoiceRepository** — manages `Invoice` — @Query: 0
- **InvoiceSequenceRepository** — manages `InvoiceSequence` — @Query: 1
- **RunTopupRepository** — manages `RunTopup` — @Query: 7 (per-user / per-org remaining balance, expiry)
- **SellerConfigRepository** — manages `SellerConfig` — @Query: 0
- **TopupPackageRepository** — manages `TopupPackage` — @Query: 0
- **WebhookEventLogRepository** — manages `WebhookEventLog` — @Query: 0

### codeintel/
- **FilePurposeSummaryRepository** — manages `FilePurposeSummary` — @Query: 0
- **ProjectArchitectureDocRepository** — manages `ProjectArchitectureDoc` — @Query: 0
- **ProjectCodeMapRepository** — manages `ProjectCodeMap` — @Query: 0
- **ProjectGeneratedDocRepository** — manages `ProjectGeneratedDoc` — @Query: 0
- **RepoArchitectureDocRepository** — manages `RepoArchitectureDoc` — @Query: 0
- **RunCodeMapRepository** — manages `RunCodeMap` — @Query: 0

### codereview/
- **CodeReviewIssueRepository** — manages `CodeReviewIssue` — @Query: 0
- **CodeReviewScanRepository** — manages `CodeReviewScan` — @Query: 3 (per-user monthly count, per-project cost sum)

### config/
- **FeatureFlagRepository** — manages `FeatureFlag` — @Query: 0

### contracts/
- **ContractConsumerRepository** — manages `ContractConsumer` — @Query: 0
- **ContractDriftEventRepository** — manages `ContractDriftEvent` — @Query: 1 (open drift by contract)
- **ContractRegistryRepository** — manages `ContractRegistry` — @Query: 0
- **ContractVersionRepository** — manages `ContractVersion` — @Query: 0

### cost/
- **RunCostHistoryRepository** — manages `RunCostHistory` — @Query: 2 (nearest-neighbour cost lookups for estimation)

### deployment/
- **DeploymentCredentialRepository** — manages `DeploymentCredential` — @Query: 2 (user+cloud lookup, delete)
- **PreviewMetricRepository** — manages `PreviewMetric` — @Query: 0
- **PreviewRegistryRepository** — manages `PreviewRegistry` — @Query: 2 (avg deploy duration, VM occupancy histogram)

### execution/
- **ExecutionContextHandoverRepository** — manages `ExecutionContextHandover` — @Query: 0
- **ExecutionRunRepository** — manages `ExecutionRun` — @Query: 8 (project paging, soft-delete filters)
- **ExecutionSolutionRepoRepository** — manages `ExecutionSolutionRepo` — @Query: 0
- **ExecutionSolutionRepository** — manages `ExecutionSolution` — @Query: 0
- **ExecutionSolutionRunRepository** — manages `ExecutionSolutionRun` — @Query: 0

### folder/
- **ProjectConfigRepository** — manages `ProjectConfig` — @Query: 0

### harness/
- **GateRepository** — manages `GateRecord` — @Query: 1
  - `UPDATE GateRecord g SET g.status = :newStatus, g.resolvedAt = :now WHERE g.gateKey = :gateKey AND g.status = 'PENDING'`

### integration/ (slack)
- **OrgSlackIntegrationRepository** — manages `OrgSlackIntegration` — @Query: 0
- **ProjectSlackChannelRepository** — manages `ProjectSlackChannel` — @Query: 0
- **SlackNotificationLogRepository** — manages `SlackNotificationLog` — @Query: 1 (`DELETE FROM SlackNotificationLog l WHERE l.createdAt < :cutoff`)

### memory/
- **ProjectChatMessageRepository** — manages `ProjectChatMessage` — @Query: 1 (`DELETE FROM ProjectChatMessage m WHERE m.projectId = :projectId`)
- **ProjectMemorySnapshotRepository** — manages `ProjectMemorySnapshot` — @Query: 0
- **ProjectRunLedgerRepository** — manages `ProjectRunLedger` — @Query: 1
  - `SELECT l FROM ProjectRunLedger l WHERE l.projectId = :projectId AND l.compactedInto IS NULL AND l.runNumber <= :beforeRunNumber ORDER BY l.runNumber ASC`

### migration/
- **MigrationDependencyRepository** — manages `MigrationDependency` — @Query: 0
- **MigrationDryRunCacheRepository** — manages `MigrationDryRunCache` — @Query: 0
- **MigrationRecipeRepository** — manages `MigrationRecipe` — @Query: 0
- **MigrationSlotRepository** — manages `MigrationSlot` — @Query: 0
- **MigrationVariantRepository** — manages `MigrationVariant` — @Query: 0

### notification/
- **NotificationRepository** — manages `Notification` — @Query: 2 (mark-all-read, mark-one-read)
- **UserNotificationPrefRepository** — manages `UserNotificationPref` — @Query: 0

### pricing/
- **ModelPricingRepository** — manages `ModelPricing` — @Query: 0

### project/
- **PullRequestRecordRepository** — manages `PullRequestRecord` — @Query: 0
- **ProjectDocumentRepository** — manages `ProjectDocument` — @Query: 0
- **ProjectRepoLinkRepository** — manages `ProjectRepoLink` — @Query: 0
- **ProjectRepoRepository** — manages `ProjectRepo` — @Query: 0
- **ProjectSpecTemplateRepository** — manages `ProjectSpecTemplate` — @Query: 0

### prreview/
- **PrCommentInboxRepository** — manages `PrCommentInbox` — @Query: 0
- **PrReviewCommentRepository** — manages `PrReviewComment` — @Query: 0
- **PrReviewRepository** — manages `PrReview` — @Query: 0

### readymade/
- **ReadymadeDependencyRepository** — manages `ReadymadeDependency` — @Query: 0
- **ReadymadeFavouriteRepository** — manages `ReadymadeFavourite` — @Query: 0
- **ReadymadeSlotRepository** — manages `ReadymadeSlot` — @Query: 0
- **ReadymadeSolutionRepository** — manages `ReadymadeSolution` — @Query: 0
- **ReadymadeVariantRepository** — manages `ReadymadeVariant` — @Query: 0

### referral/
- **ReferralInviteRepository** — manages `ReferralInvite` — @Query: 4 (email lookup, pending invites, grant status)

### report/
- **ValidationReportRepository** — manages `ValidationReport` — @Query: 8 (project-scoped joins, terminal-count, time-series, native queries)

### router/
- **ModelTaskMetricRepository** — manages `ModelTaskMetric` — @Query: 1 (`SELECT m FROM ModelTaskMetric m ORDER BY m.createdAt DESC`)

### saga/
- **SideEffectOutboxRepository** — manages `SideEffectOutboxEntry` — @Query: 2 (`PENDING`/`PROCESSING` lookup with retry-time gate, transactional PENDING→PROCESSING flip)

### scoring/
- **RunRubricRowRepository** — manages `RunRubricRow` — @Query: 0

### sharing/
- **ProjectInviteRepository** — manages `ProjectInvite` — @Query: 0
- **ProjectShareLinkRepository** — manages `ProjectShareLink` — @Query: 1 (active-count by project)

### standards/
- **OrgConfigRepoRepository** — manages `OrgConfigRepo` — @Query: 0
- **OrgStandardRepository** — manages `OrgStandard` — @Query: 0
- **ProjectStandardOverrideRepository** — manages `ProjectStandardOverride` — @Query: 0
- **StandardsViolationRepository** — manages `StandardsViolation` — @Query: 2 (recent-by-project, override audit)

### state/ (run lifecycle — the hot tables)
- **AgentInboxRepository** — manages `AgentInbox` — @Query: 1 (mark delivered)
- **AgentMessageRepository** — manages `AgentMessage` — @Query: 0
- **AgentTaskRepository** — manages `AgentTask` — @Query: 2 (atomic PENDING→LOCKED, PENDING→WORKING transitions)
- **CompactionEventRepository** — manages `CompactionEvent` — @Query: 0
- **DeploymentRepository** — manages `Deployment` — @Query: 3 (active-count by user/run/status)
- **FileLockRepository** — manages `FileLock` — @Query: 0
- **GateEventRepository** — manages `GateEvent` — @Query: 0
- **IntegrationEventLogRepository** — manages `IntegrationEventLog` — @Query: 1 (GC by cutoff)
- **IntegrationRunRepository** — manages `IntegrationRun` — @Query: 0
- **MergeConflictRepository** — manages `MergeConflict` — @Query: 2 (per-file analytics rollup)
- **PipelineRunRepository** — manages `PipelineRun` — @Query: **34** (the largest custom-query surface in the codebase; covers token accumulation, status transitions, gate clearing, archive lifecycle, heartbeat updates, batch progress, planner outputs, fork/iteration parent links, runs-by-status, recovery candidates, and finance backfills)
- **PlanVersionRepository** — manages `PlanVersion` — @Query: 0
- **PromptAuditRepository** — manages `PromptAuditEntry` — @Query: 1 (latest version per prompt file)
- **RunRecoveryRepository** — manages `RunRecovery` — @Query: 1 (classification × original-run-id IN-clause)
- **RunRepoRepository** — manages `RunRepo` — @Query: 0
- **RunRepoSnapshotRepository** — manages `RunRepoSnapshot` — @Query: 0

### testgen/
- **TestPlanRepository** — manages `TestPlan` — @Query: 0
- **TestResultRepository** — manages `TestResult` — @Query: 0
- **TestTaskRepository** — manages `TestTask` — @Query: 0

---

## 6. Entities (data layer)

JPA `@Entity` classes. Counts are `@Column` annotations + private fields (some fields use `@JoinColumn`/`@Embedded` and don't have a `@Column`). FK column patterns are detected from `@Column(name="..._id")` annotations.

### auth/
- **ActivityAudit** — `auth/entity` — table `activity_audit` — @Column: 12 | fields: 12 — FK: actor_id, entity_id, org_id
- **AppUser** — `auth/entity` — table `app_user` — @Column: 45 | fields: 34 — FK: oauth_id, org_id, referred_by_user_id, sandbox_project_id, stripe_customer_id
- **EmailToken** — `auth/entity` — table `email_token` — @Column: 6 | fields: 6 — FK: user_id
- **Org** — `auth/entity` — table `org` — @Column: 44 | fields: 29 — FK: created_by_user_id, github_installation_id, payment_ref_id, stripe_customer_id
- **OrgInvitation** — `auth/entity` — table `org_invitation` — @Column: 11 | fields: 9 — FK: org_id
- **OrgMember** — `auth/entity` — table `org_member` — @Column: 4 | fields: 3 — FK: org_id, user_id
- **PaymentAudit** — `auth/entity` — table `payment_audit` — @Column: 18 | fields: 18 — FK: entity_id, gateway_event_id, gateway_payment_id, org_id, payment_ref_id, user_id
- **Project** — `auth/entity` — table `project` — @Column: 17 | fields: 10 — FK: org_id
- **ProjectMember** — `auth/entity` — table `project_member` — @Column: 5 | fields: 3 — FK: project_id, user_id
- **RefreshTokenBlocklist** — `auth/entity` — table `refresh_token_blocklist` — @Column: 3 | fields: 3 — FK: user_id
- **TierConfig** — `auth/entity` — table `tier_config` — @Column: 26 | fields: 10 — global tier configuration (run quotas editable by super admin)

### billing/
- **AccountingEntry** — `billing/entity` — table `accounting_entry` — @Column: 14 | fields: 14 — FK: invoice_id, org_id, user_id
- **BillingPlan** — `billing/entity` — table `billing_plan` — @Column: 23 | fields: 23 — FK: razorpay_plan_id, stripe_price_id, stripe_product_id
- **BillingPlanLimit** — `billing/entity` — table `billing_plan_limit` — @Column: 3 | fields: 4 — FK: billing_plan_id
- **BillingProfile** — `billing/entity` — table `billing_profile` — @Column: 18 | fields: 17 — FK: org_id, user_id
- **BillingSubscription** — `billing/entity` — table `billing_subscription` — @Column: 24 | fields: 24 — FK: billing_entity_id, billing_plan_id, gateway_customer_id, gateway_subscription_id, org_id, user_id
- **BillingUsage** — `billing/entity` — table `billing_usage` — @Column: 7 | fields: 8 — FK: org_id, user_id
- **BillingWebhookEvent** — `billing/entity` — table `billing_webhook_event` — @Column: 8 | fields: 9 — FK: event_id
- **FounderPricingAllocation** — `billing/entity` — table `founder_pricing_allocation` — @Column: 6 | fields: 7 — FK: discount_id, org_id, user_id
- **FounderPricingDiscount** — `billing/entity` — table `founder_pricing_discount` — @Column: 13 | fields: 14 — FK: razorpay_offer_id, stripe_coupon_id (V177 singleton "first N" offer)
- **Invoice** — `billing/entity` — table `invoice` — @Column: 50 | fields: 38 — FK: billing_profile_id, gateway_invoice_id, gateway_order_id, gateway_payment_id, org_id, original_invoice_id, subscription_id, topup_id, user_id
- **InvoiceLineItem** — `billing/entity` — table `invoice_line_item` — @Column: 8 | fields: 6 — FK: invoice_id
- **InvoiceSequence** — `billing/entity` — table `invoice_sequence` — @Column: 2 | fields: 2
- **RunTopup** — `billing/entity` — table `run_topup` — @Column: 14 | fields: 15 — FK: gateway_order_id, gateway_payment_id, org_id, user_id
- **SellerConfig** — `billing/entity` — table `seller_config` — @Column: 13 | fields: 11
- **TopupPackage** — `billing/entity` — table `topup_package` — @Column: 8 | fields: 9
- **WebhookEventLog** — `billing/entity` — table `webhook_event_log` — @Column: 9 | fields: 8 — FK: event_id

### codeintel/
- **FilePurposeSummary** — `codeintel/entity` — table `file_purpose_summary` — @Column: 9 | fields: 5 (V196 per-file LLM-generated purpose, SHA-keyed cache)
- **ProjectArchitectureDoc** — `codeintel/entity` — table `project_architecture_doc` — @Column: 8 | fields: 5 — FK: project_id
- **ProjectCodeMap** — `codeintel/entity` — table `project_code_map` — @Column: 6 | fields: 4 — FK: project_id, repo_id
- **ProjectGeneratedDoc** — `codeintel/entity` — table `project_generated_doc` — @Column: 13 | fields: 9 — FK: project_id
- **RepoArchitectureDoc** — `codeintel/entity` — table `repo_architecture_doc` — @Column: 13 | fields: 9 — FK: project_id, repo_id, source_code_map_id
- **RunCodeMap** — `codeintel/entity` — table `run_code_map` — @Column: 11 | fields: 6 — FK: run_id

### codereview/
- **CodeReviewIssue** — `codereview/entity` — table `code_review_issue` — @Column: 15 | fields: 13 — FK: resolved_by_user_id, rule_id, scan_id
- **CodeReviewScan** — `codereview/entity` — table `code_review_scan` — @Column: 20 | fields: 11 — FK: created_by_user_id, project_id, repo_id

### execution/
- **ExecutionContextHandover** — `execution/entity` — table `execution_context_handover` — @Column: 6 | fields: 6 — FK: from_solution_id, to_solution_id
- **ExecutionRun** — `execution/entity` — table `execution_run` — @Column: 32 | fields: 29 — FK: migration_recipe_id, migration_variant_id, org_id, pipeline_run_id, project_id, readymade_solution_id, readymade_variant_id, source_execution_id
- **ExecutionSolution** — `execution/entity` — table `execution_solution` — @Column: 27 | fields: 22 — FK: jira_ticket_id, pipeline_run_id
- **ExecutionSolutionRepo** — `execution/entity` — table `execution_solution_repo` — @Column: 6 | fields: 6 — FK: repo_id
- **ExecutionSolutionRun** — `execution/entity` — table `execution_solution_run` — @Column: 11 | fields: 11 — FK: pipeline_run_id, solution_id

### harness/
- **GateRecord** — `harness/entity` — table `gate_record` — @Column: 7 | fields: 7 — FK: run_id (durable approval gate decisions)

### memory/
- **ProjectChatMessage** — `memory/entity` — table `project_chat_message` — @Column: 9 | fields: 9 — FK: org_id, project_id, user_id
- **ProjectMemorySnapshot** — `memory/entity` — table `project_memory_snapshot` — @Column: 9 | fields: 9 — FK: org_id, project_id
- **ProjectRunLedger** — `memory/entity` — table `project_run_ledger` — @Column: 9 | fields: 9 — FK: org_id, project_id, run_id

### migration/
- **MigrationDependency** — `migration/entity` — table `migration_dependency` — @Column: 3 | fields: 5 — FK: depends_on_id, recipe_id
- **MigrationDryRunCache** — `migration/entity` — table `migration_dry_run_cache` — @Column: 8 | fields: 9 — FK: recipe_id
- **MigrationRecipe** — `migration/entity` — table `migration_recipe` — @Column: 26 | fields: 21 — FK: author_org_id, author_user_id
- **MigrationSlot** — `migration/entity` — table `migration_slot` — @Column: 9 | fields: 7 — FK: recipe_id
- **MigrationVariant** — `migration/entity` — table `migration_variant` — @Column: 8 | fields: 7 — FK: recipe_id

### project/
- **ProjectRepo** — `project/entity` — table `project_repo` — @Column: 19 | fields: 14 — FK: owner_org_id, owner_user_id (dual-scoped repo registry)
- **ProjectRepoLink** — `project/entity` — table `project_repo_link` — @Column: 5 | fields: 4 — FK: project_id, repo_id (project-attach join row)

### prreview/
- **PrReview** — `prreview/entity` — table `pr_review` — @Column: 17 | fields: 13 — FK: created_by_user_id, github_review_id, project_id
- **PrReviewComment** — `prreview/entity` — table `pr_review_comment` — @Column: 10 | fields: 9 — FK: github_comment_id, pr_review_id

### rag/
- **ProjectDocument** — `rag/entity` — table `project_document` — @Column: 14 | fields: 14 — FK: project_id

### readymade/
- **ReadymadeDependency** — `readymade/entity` — table `readymade_dependency` — @Column: 3 | fields: 4 — FK: depends_on_id, solution_id
- **ReadymadeFavourite** — `readymade/entity` — table `readymade_favourite` — @Column: 3 | fields: 4 — FK: solution_id, user_id
- **ReadymadeSlot** — `readymade/entity` — table `readymade_slot` — @Column: 9 | fields: 7 — FK: solution_id
- **ReadymadeSolution** — `readymade/entity` — table `readymade_solution` — @Column: 22 | fields: 17 — FK: author_org_id, author_user_id
- **ReadymadeVariant** — `readymade/entity` — table `readymade_variant` — @Column: 9 | fields: 8 — FK: solution_id

### report/
- **PipelineResult** — `report/entity` — table `` — @Column: 0 | fields: 0 (legacy non-JPA helper)
- **ValidationReport** — `report/entity` — table `validation_report` — @Column: 33 | fields: 27 — FK: run_id

### scoring/
- **RunRubricRow** — `scoring/entity` — table `run_rubric_row` — @Column: 11 | fields: 8 — FK: rubric_row_id, run_id (V197 planner-emitted rubric per run)

### sharing/
- **ProjectInvite** — `sharing/entity` — table `project_invite` — @Column: 13 | fields: 10 — FK: accepted_by_user_id, invited_by_user_id, invited_org_id, project_id, revoked_by_user_id (V175 cross-org invite)
- **ProjectShareLink** — `sharing/entity` — table `project_share_link` — @Column: 12 | fields: 10 — FK: created_by_user_id, project_id, revoked_by_user_id, scope_id (V175 public share link)

### state/ (run lifecycle)
- **AgentInbox** — `state/entity` — table `agent_inbox` — @Column: 1 | fields: 11
- **AgentMessage** — `state/entity` — table `agent_message` — @Column: 2 | fields: 10 — FK: tenant_id
- **AgentTask** — `state/entity` — table `agent_task` — @Column: 19 | fields: 33 — FK: parent_task_id, tenant_id
- **CompactionEvent** — `state/entity` — table `compaction_event` — @Column: 0 | fields: 6
- **Deployment** — `state/entity` — table `deployment` — @Column: 35 | fields: 34 — FK: credential_id, run_id, run_repo_id, tenant_id, user_id
- **FileLock** — `state/entity` — table `file_lock` — @Column: 0 | fields: 4
- **GateEvent** — `state/entity` — table `gate_event` — @Column: 1 | fields: 6 — FK: tenant_id
- **IntegrationEventLog** — `state/entity` — table `integration_event_log` — @Column: 4 | fields: 4 — FK: event_id, tenant_id
- **IntegrationRun** — `state/entity` — table `integration_run` — @Column: 6 | fields: 6 — FK: run_id, source_id, tenant_id
- **MergeConflict** — `state/entity` — table `merge_conflict` — @Column: 10 | fields: 10 — FK: run_id, task_id (analytics → conflict-resolver evolution)
- **PipelineRun** — `state/entity` — table `pipeline_run` — @Column: **87** | fields: 91 — FK: batch_id, created_by_user_id, org_id, paused_task_id, project_id, source_run_id, tenant_id (the run-state hub; ~100 fields covering plan, costs, score, gates, archives, batch, ownership, retries)
- **PlanVersion** — `state/entity` — table `plan_version` — @Column: 6 | fields: 6 — FK: run_id
- **PromptAuditEntry** — `state/entity` — table `prompt_audit_entry` — @Column: 11 | fields: 10 (versioned prompt-file history)
- **RunRecovery** — `state/entity` — table `run_recovery` — @Column: 10 | fields: 10 — FK: original_run_id, restart_run_id, stuck_task_id, tenant_id
- **RunRepo** — `state/entity` — table `run_repo` — @Column: 9 | fields: 8 — FK: run_id, tenant_id
- **RunRepoSnapshot** — `state/entity` — table `run_repo_snapshot` — @Column: 7 | fields: 6 — FK: repo_id, run_id (commit SHA captured at run start; used by `/api/runs/{id}/sync/original`)

---

## Cross-reference

For the package-level overview (which package holds what, dependency graph, migration log per package), see [`CODEMAP.md`](CODEMAP.md). For the LLM-pipeline architecture (run orchestrator → planner → worker → reviewer → preview), see [`ARCHITECTURE.md`](ARCHITECTURE.md). For RBAC matrix per endpoint, see [`RBAC.md`](RBAC.md).

This doc reflects the public Java surface as of head `V199` (`1.0.0-M1`). Endpoint signatures may include framework-level wrappers (`@AuthenticationPrincipal`, `@PathVariable`, `@RequestBody`) elided from the compact form; consult the source for binding details.
