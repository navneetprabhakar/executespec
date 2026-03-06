// Typed API client — all backend endpoints

const BASE = '/api';

/** Returns the stored access token for authenticated requests. */
function authHeader(): Record<string, string> {
  const token = localStorage.getItem('access_token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function req<T>(url: string, opts?: RequestInit): Promise<T> {
  const res = await fetch(BASE + url, {
    headers: { 'Content-Type': 'application/json', ...authHeader(), ...opts?.headers },
    credentials: 'include',  // send refresh_token cookie
    ...opts,
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => res.statusText);
    throw new Error(`${res.status}: ${msg}`);
  }
  if (res.status === 204) return undefined as unknown as T;
  // Handle Spring returning 200 OK with an empty body (e.g. approve/merge endpoints)
  const text = await res.text();
  if (!text.trim()) return undefined as unknown as T;
  return JSON.parse(text);
}

/** Fetch a plain-text endpoint (e.g. file content served as text/plain). */
async function reqText(url: string): Promise<string> {
  const res = await fetch(BASE + url, {
    headers: { ...authHeader() },  // include JWT so protected endpoints return 200 not 403
    credentials: 'include',
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => res.statusText);
    throw new Error(`${res.status}: ${msg}`);
  }
  return res.text();
}

// ── Run types ───────────────────────────────────────────────────────────────

export interface RunSummary {
  runId: string;
  status: string;
  userRequest: string;
  plannerModel: string;
  workerModel: string;
  reviewerModel: string;
  totalInputTokens: number;
  totalOutputTokens: number;
  startedAt: string;
  completedAt: string | null;
  /** Project this run belongs to, or null for un-grouped runs. */
  projectId?: string | null;
  /** GitHub repo URL populated after a successful post-merge push; null otherwise. */
  repoUrl?: string | null;
  /** Whether this run was started by cloning an existing repo. */
  repoMode?: string | null;
}

export interface TaskSummary {
  taskId: string;
  title: string;
  status: string;
  assignedModel: string;
  inputTokens: number;
  outputTokens: number;
  durationMs: number;
  branchName: string | null;
  startedAt: string | null;
  completedAt: string | null;
  readmeUpdated: boolean;
  /** Present when the task ended in PARTIAL / BLOCKED / FAILED — explains why. */
  failureReason?: string | null;
  /** The planner's objective/description for this task — shown in the expanded card. */
  objective?: string | null;
}

// ── Review types (mirrors Java ReviewReport / TaskReview / ReviewIssue) ─────

export interface ReviewIssue {
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  file?: string | null;
  lineHint?: string | null;
  description: string;
}

export interface TaskReview {
  taskId: string;
  acceptanceCriteriaMet: boolean;
  boundaryViolations: string[];
  issues: ReviewIssue[];
  positives: string[];
}

export interface ReviewReport {
  overallConfidence: 'HIGH' | 'MEDIUM' | 'LOW';
  recommendation: 'MERGE' | 'REWORK' | 'PARTIAL_MERGE';
  summary: string;
  taskReviews: TaskReview[];
  mergeBlockers: string[];
  postMergeSuggestions: string[];
  readmeUpdated: boolean;
}

export interface RunDetail extends RunSummary {
  tasks: TaskSummary[];
  planJson: string;
  reviewJson: string;
  integrationSource: { sourceType: string; sourceId: string; sourceUrl: string } | null;
  failureReason?: string | null;
  currentGate?: string | null;
}

export interface MetricsResponse {
  totalRuns: number;
  completedRuns: number;
  failedRuns: number;
  activeRuns: number;
  queuedRuns: number;
  totalInputTokens: number;
  totalOutputTokens: number;
}

export interface PricingRow {
  provider: string;
  model: string;
  inputCostPer1m: number;
  outputCostPer1m: number;
  notes: string | null;
  updatedAt: string | null;
}

export interface TaskFeasibility {
  taskId: string;
  title: string;
  complexity: string;
  minIterations: number;
  maxIterations: number;
  estimatedLowUsd: number;
  estimatedHighUsd: number;
}

export interface FeasibilityReport {
  verdict: string;  // "FEASIBLE" | "MARGINAL" | "INFEASIBLE" | "ESTIMATED"
  budgetUsd: number | null;
  estimatedLowUsd: number;
  estimatedHighUsd: number;
  tasks: TaskFeasibility[];
}

export interface DeploymentSummary {
  id: number;
  runId: string;
  status: string;
  strategy: string;
  serviceUrl: string;
  createdAt: string;
  expiresAt: string;
}

export interface TimelineData {
  tasks: Array<{ taskId: string; title: string; startedAt: string; completedAt: string; status: string }>;
}

export interface ModelComparisonData {
  models: Array<{ provider: string; model: string; taskCount: number; totalInputTokens: number; totalOutputTokens: number; avgDurationMs: number }>;
}

// ── Runs ────────────────────────────────────────────────────────────────────

export const getRuns = (params?: { status?: string; projectId?: string }) => {
  const qs = new URLSearchParams();
  if (params?.status)    qs.set('status',    params.status);
  if (params?.projectId) qs.set('projectId', params.projectId);
  const q = qs.toString();
  return req<RunSummary[]>(`/runs${q ? `?${q}` : ''}`);
};

export const getRun = (id: string) =>
  req<RunDetail>(`/runs/${id}`);

export const createRun = (body: {
  userRequest: string;
  skipApproval?: boolean;
  modelName?: string;
  projectId?: string;
  repoMode?: string | null;
  repoUrl?: string | null;
}) =>
  req<{ runId: string }>('/runs', { method: 'POST', body: JSON.stringify(body) });

export const approveRun = (id: string, approved: boolean) =>
  req<void>(`/runs/${id}/approve`, { method: 'POST', body: JSON.stringify({ approved }) });

export const mergeRun = (id: string, merge: boolean) =>
  req<void>(`/runs/${id}/merge`, { method: 'POST', body: JSON.stringify({ merge }) });

export const cancelRun = (id: string) =>
  req<void>(`/runs/${id}`, { method: 'DELETE' });

export const retryTask = (runId: string, taskId: string) =>
  req<void>(`/runs/${runId}/tasks/${taskId}/retry`, { method: 'POST' });

export const retryRun = (runId: string) =>
  req<RunSummary>(`/runs/${runId}/retry`, { method: 'POST' });

export const reopenMergeGate = (runId: string) =>
  req<void>(`/runs/${runId}/reopen-merge`, { method: 'POST' });

export const implementSuggestions = (runId: string) =>
  req<void>(`/runs/${runId}/implement-suggestions`, { method: 'POST' });

// ── Metrics ─────────────────────────────────────────────────────────────────

export const getMetrics = () => req<MetricsResponse>('/metrics');

export const getModelComparison = (days = 30) =>
  req<ModelComparisonData>(`/model-comparison?days=${days}`);

// ── Pricing ─────────────────────────────────────────────────────────────────

export const getPricing = () => req<PricingRow[]>('/pricing');

export const updatePricing = (provider: string, model: string, body: Partial<PricingRow>) =>
  req<PricingRow>(`/pricing/${provider}/${model}`, { method: 'PUT', body: JSON.stringify(body) });

// ── Feasibility ──────────────────────────────────────────────────────────────

export const feasibility = (body: { goal: string; provider?: string; model?: string; budgetUsd?: number }) =>
  req<FeasibilityReport>('/feasibility', { method: 'POST', body: JSON.stringify(body) });

// ── Deployments ──────────────────────────────────────────────────────────────

export const getDeployments = () => req<DeploymentSummary[]>('/deployments');

export const createDeployment = (body: { runId: string; strategy?: string }) =>
  req<DeploymentSummary>('/deployments', { method: 'POST', body: JSON.stringify(body) });

export const deleteDeployment = (id: number) =>
  req<void>(`/deployments/${id}`, { method: 'DELETE' });

export const rollbackDeployment = (id: number) =>
  req<DeploymentSummary>(`/deployments/${id}/rollback`, { method: 'POST' });

// ── Integrations ─────────────────────────────────────────────────────────────

export const getIntegrationStatus = () => req<{ enabledSources: string[] }>('/integrations/status');

export const getIntegrationRuns = (source?: string) =>
  req<RunSummary[]>(`/integrations/runs${source ? `?source=${source}` : ''}`);

export const triggerConfluence = (body: { pageId: string; goal: string }) =>
  req<{ runId: string }>('/integrations/confluence/trigger', { method: 'POST', body: JSON.stringify(body) });

// ── Timeline ──────────────────────────────────────────────────────────────────

export const getTimeline = (runId: string) =>
  req<TimelineData>(`/runs/${runId}/timeline`);

// ── Files ─────────────────────────────────────────────────────────────────────

export const getFiles = (runId: string) =>
  req<string[]>(`/runs/${runId}/files`);

/**
 * Trigger a browser download of the workspace zip.
 * Uses fetch with auth header, then creates an object URL for the browser download.
 */
export async function downloadWorkspace(runId: string): Promise<void> {
  const res = await fetch(`${BASE}/runs/${runId}/download`, {
    headers: { ...authHeader() },
  });
  if (!res.ok) throw new Error(`Download failed: ${res.status}`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `workspace-${runId.slice(0, 8)}.zip`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/** File content is served as text/plain — must NOT use res.json(). */
export const getFile = (runId: string, filePath: string) =>
  reqText(`/runs/${runId}/file?path=${encodeURIComponent(filePath)}`);

// ── Messages (historical log) ────────────────────────────────────────────────

export interface MessageResponse {
  fromAgent: string;
  contentSummary: string;
  sentAt: string;
}

export const getMessages = (runId: string) =>
  req<MessageResponse[]>(`/runs/${runId}/messages`);

// ── OpenAPI spec ─────────────────────────────────────────────────────────────

/** Fetch the generated OpenAPI YAML for a run (served as text/plain). */
export const getOpenApiSpec = (runId: string) =>
  reqText(`/runs/${runId}/openapi.yaml`);

// ── Local Docker Preview ──────────────────────────────────────────────────────

export interface LocalPreviewStatus {
  runId: string;
  status: 'IDLE' | 'BUILDING' | 'RUNNING' | 'FAILED' | 'STOPPED';
  url: string | null;
  projectType: string | null;
  errorMessage: string | null;
}

export const getPreviewStatus = (runId: string) =>
  req<LocalPreviewStatus>(`/runs/${runId}/preview`);

export const startPreview = (runId: string) =>
  req<LocalPreviewStatus>(`/runs/${runId}/preview/start`, { method: 'POST' });

export const stopPreview = (runId: string) =>
  req<void>(`/runs/${runId}/preview/stop`, { method: 'DELETE' });

// ── Conversation (pre-flight chat) ────────────────────────────────────────────

export interface ConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}

export const conversationChat = (messages: ConversationMessage[]) =>
  req<{ reply: string }>('/conversation', {
    method: 'POST',
    body: JSON.stringify({ messages }),
  });

// ── Post-run conversation & iteration ──────────────────────────────────────────

/** A single message in a post-run conversation. */
export interface RunChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

/** Response from POST /api/runs/{id}/chat */
export interface ChatResponse {
  reply: string;
  /** Number of user messages sent so far. */
  messageCount: number;
  /** Maximum user messages allowed. */
  maxMessages: number;
}

/** Returns the full conversation history for a completed run. */
export const getRunChat = (runId: string) =>
  req<RunChatMessage[]>(`/runs/${runId}/chat`);

/** Sends a user message and returns the assistant reply. */
export const sendRunChatMessage = (runId: string, message: string) =>
  req<ChatResponse>(`/runs/${runId}/chat`, {
    method: 'POST',
    body: JSON.stringify({ message }),
  });

/** Creates a new iteration run that starts from the source run's codebase. */
export const iterateRun = (runId: string, userRequest: string) =>
  req<{ runId: string }>(`/runs/${runId}/iterate`, {
    method: 'POST',
    body: JSON.stringify({ userRequest }),
  });

// ── Auth types & endpoints ────────────────────────────────────────────────────

export interface AuthUser {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  globalRole: 'SUPER_ADMIN' | 'USER';
  tier: 'FREE' | 'PRO' | 'ENTERPRISE';
  status: string;
  mustChangePassword: boolean;
  emailVerified: boolean;
  orgId: string | null;
  orgRole: 'ORG_ADMIN' | 'DEVELOPER' | 'BILLING_ADMIN' | null;
  /** GitHub username or org name the user wants repos pushed under. */
  githubOwner?: string | null;
  /** True if the user has a GitHub PAT saved (raw PAT is never returned). */
  hasGithubPat?: boolean;
  // AI provider key flags
  hasOpenAiKey?: boolean;
  hasAnthropicKey?: boolean;
  hasGeminiKey?: boolean;
  // JIRA
  jiraBaseUrl?: string | null;
  jiraEmail?: string | null;
  hasJiraToken?: boolean;
  // Confluence
  confluenceBaseUrl?: string | null;
  confluenceEmail?: string | null;
  hasConfluenceToken?: boolean;
}

export interface LoginResponse {
  accessToken: string;
  user: AuthUser;
}

export const authRegister = (body: { email: string; firstName: string; lastName: string; password: string }) =>
  req<AuthUser>('/auth/register', { method: 'POST', body: JSON.stringify(body) });

export const authLogin = (body: { email: string; password: string }) =>
  req<LoginResponse>('/auth/login', { method: 'POST', body: JSON.stringify(body) });

export const authRefresh = () =>
  req<LoginResponse>('/auth/refresh', { method: 'POST' });

export const authLogout = () =>
  req<void>('/auth/logout', { method: 'DELETE' });

export const authMe = () =>
  req<AuthUser>('/auth/me');

export const authVerifyEmail = (token: string) =>
  req<{ message: string }>(`/auth/verify-email?token=${token}`);

export const authResendVerification = (email: string) =>
  req<{ message: string }>('/auth/resend-verification', { method: 'POST', body: JSON.stringify({ email }) });

export const authForgotPassword = (email: string) =>
  req<{ message: string }>('/auth/forgot-password', { method: 'POST', body: JSON.stringify({ email }) });

export const authResetPassword = (token: string, newPassword: string) =>
  req<{ message: string }>('/auth/reset-password', { method: 'POST', body: JSON.stringify({ token, newPassword }) });

export const authChangePassword = (currentPassword: string | null, newPassword: string) =>
  req<{ message: string }>('/auth/change-password', {
    method: 'POST',
    body: JSON.stringify({ currentPassword, newPassword }),
  });

/**
 * Saves the user's GitHub PAT and owner (username or org).
 * Pass empty strings to clear the credentials.
 */
export const updateGithubSettings = (githubPat: string, githubOwner: string) =>
  req<AuthUser>('/auth/settings/github', {
    method: 'PUT',
    body: JSON.stringify({ githubPat, githubOwner }),
  });

/** Saves per-user AI provider API keys. Pass empty string to clear. */
export const updateAiKeySettings = (openAiApiKey: string, anthropicApiKey: string, geminiApiKey: string) =>
  req<AuthUser>('/auth/settings/ai-keys', {
    method: 'PUT',
    body: JSON.stringify({ openAiApiKey, anthropicApiKey, geminiApiKey }),
  });

/** Saves per-user JIRA credentials. Pass empty strings to clear. */
export const updateJiraSettings = (jiraBaseUrl: string, jiraEmail: string, jiraApiToken: string) =>
  req<AuthUser>('/auth/settings/jira', {
    method: 'PUT',
    body: JSON.stringify({ jiraBaseUrl, jiraEmail, jiraApiToken }),
  });

/** Saves per-user Confluence credentials. Pass empty strings to clear. */
export const updateConfluenceSettings = (confluenceBaseUrl: string, confluenceEmail: string, confluenceApiToken: string) =>
  req<AuthUser>('/auth/settings/confluence', {
    method: 'PUT',
    body: JSON.stringify({ confluenceBaseUrl, confluenceEmail, confluenceApiToken }),
  });

// ── Org endpoints ─────────────────────────────────────────────────────────────

export interface OrgDto {
  id: string;
  name: string;
  domainPatterns: string;
  tier: string;
  status: string;
  monthlyRunCount: number;
  isPaymentVerified: boolean;
  monthlyRunLimitOverride: number | null;
  apiKeysProvided: boolean;
  planExpiresAt: string | null;
  createdAt: string;
  // Per-org AI provider key flags (raw keys never returned)
  hasAnthropicKey?: boolean;
  hasOpenAiKey?: boolean;
  hasGeminiKey?: boolean;
}

export interface OrgMemberDto {
  userId: string;
  email: string;
  firstName: string;
  lastName: string;
  orgRole: string;
  joinedAt: string;
}

export interface OrgInvitation {
  id: string;
  orgId: string;
  invitedEmail: string;
  orgRole: string;
  status: string;
  expiresAt: string;
  createdAt: string;
}

export const getMyOrg = () => req<OrgDto>('/org/me');

export const createOrg = (body: { name: string; domains: string[] }) =>
  req<OrgDto>('/org', { method: 'POST', body: JSON.stringify(body) });

export const updateOrg = (body: { name?: string; domains?: string[] }) =>
  req<OrgDto>('/org/me', { method: 'PUT', body: JSON.stringify(body) });

/** Saves org-wide AI provider API keys (ORG_ADMIN only). Pass empty string to clear. */
export const updateOrgAiKeySettings = (anthropicApiKey: string, openAiApiKey: string, geminiApiKey: string) =>
  req<OrgDto>('/org/me/settings/ai-keys', {
    method: 'PUT',
    body: JSON.stringify({ anthropicApiKey, openAiApiKey, geminiApiKey }),
  });

export const getOrgMembers = () => req<OrgMemberDto[]>('/org/me/members');

export const removeMember = (userId: string) =>
  req<void>(`/org/me/members/${userId}`, { method: 'DELETE' });

export const changeMemberRole = (userId: string, role: string) =>
  req<void>(`/org/me/members/${userId}/role`, { method: 'PUT', body: JSON.stringify({ role }) });

export const inviteMember = (body: { email: string; orgRole?: string; projectIds?: string[] }) =>
  req<{ message: string; status: string }>('/org/invite', { method: 'POST', body: JSON.stringify(body) });

export const getOrgInvitations = () => req<OrgInvitation[]>('/org/me/invitations');

export const acceptInvite = (token: string) =>
  req<{ message: string }>(`/org/accept-invite?token=${token}`, { method: 'POST' });

// ── Project endpoints ─────────────────────────────────────────────────────────

export interface ProjectDto {
  id: string;
  orgId: string;
  name: string;
  description: string | null;
  status: string;
  createdAt: string;
}

export interface ProjectMemberDto {
  userId: string;
  email: string;
  firstName: string;
  lastName: string;
  projectRole: string;
  assignedAt: string;
}

export const getProjects = () => req<ProjectDto[]>('/projects');

export const createProject = (body: { name: string; description?: string }) =>
  req<ProjectDto>('/projects', { method: 'POST', body: JSON.stringify(body) });

export const archiveProject = (id: string) =>
  req<ProjectDto>(`/projects/${id}/archive`, { method: 'PUT' });

export const getProjectMembers = (projectId: string) =>
  req<ProjectMemberDto[]>(`/projects/${projectId}/members`);

export const addProjectMember = (projectId: string, userId: string, role: string) =>
  req<void>(`/projects/${projectId}/members`, {
    method: 'POST',
    body: JSON.stringify({ userId, role }),
  });

export const removeProjectMember = (projectId: string, userId: string) =>
  req<void>(`/projects/${projectId}/members/${userId}`, { method: 'DELETE' });

// ── Admin endpoints ───────────────────────────────────────────────────────────

export const adminListUsers = () => req<AuthUser[]>('/admin/users');
export const adminGetUser = (id: string) => req<AuthUser>(`/admin/users/${id}`);
export const adminUpdateUserStatus = (id: string, status: string) =>
  req<AuthUser>(`/admin/users/${id}/status`, { method: 'PUT', body: JSON.stringify({ status }) });
export const adminUpdateUserTier = (id: string, tier: string) =>
  req<AuthUser>(`/admin/users/${id}/tier`, { method: 'PUT', body: JSON.stringify({ tier }) });

export const adminListOrgs = () => req<OrgDto[]>('/admin/orgs');
export const adminUpdateOrgTier = (id: string, tier: string) =>
  req<OrgDto>(`/admin/orgs/${id}/tier`, { method: 'PUT', body: JSON.stringify({ tier }) });
export const adminUpdateOrgStatus = (id: string, status: string) =>
  req<OrgDto>(`/admin/orgs/${id}/status`, { method: 'PUT', body: JSON.stringify({ status }) });

export const adminListEscalated = () => req<OrgInvitation[]>('/admin/invitations/escalated');
export const adminApproveInvite = (id: string) =>
  req<{ message: string }>(`/admin/invitations/${id}/approve`, { method: 'POST' });
export const adminRejectInvite = (id: string) =>
  req<{ message: string }>(`/admin/invitations/${id}/reject`, { method: 'POST' });

// Tier config
export interface TierConfig { tier: string; monthlyRunLimit: number; isFixed: boolean; description: string; }
export const adminGetTierConfig = () => req<TierConfig[]>('/admin/tier-config');
export const adminUpdateTierConfig = (tier: string, monthlyRunLimit: number) =>
  req<TierConfig>(`/admin/tier-config/${tier}`, { method: 'PUT', body: JSON.stringify({ monthlyRunLimit }) });

// Per-org overrides
export const adminUpdateOrgRunLimit = (id: string, limit: number | null) =>
  req<OrgDto>(`/admin/orgs/${id}/run-limit`, { method: 'PUT', body: JSON.stringify({ limit }) });
export const adminUpdateOrgApiKeys = (id: string, provided: boolean) =>
  req<OrgDto>(`/admin/orgs/${id}/api-keys`, { method: 'PUT', body: JSON.stringify({ provided }) });

// Admin invite
export interface AdminInviteRequest {
  email: string; firstName: string; lastName: string;
  tier: string; orgId?: string; newOrgName?: string; orgRole?: string;
}
export const adminInviteUser = (body: AdminInviteRequest) =>
  req<{ message: string }>('/admin/invite', { method: 'POST', body: JSON.stringify(body) });

// Org member suspend
export const orgSuspendMember = (userId: string, suspended: boolean) =>
  req<void>(`/org/me/members/${userId}/suspend`, { method: 'PUT', body: JSON.stringify({ suspended }) });

// Task execution graph
export interface TaskGraphNode {
  taskId: string;
  title: string;
  status: string;
  complexity: string | null;
  assignedModel: string | null;
  dependsOn: string[];
  level: number;
}
export const getTaskGraph = (runId: string) => req<TaskGraphNode[]>(`/runs/${runId}/task-graph`);
