import {
  AuthUser,
  LoginResponse,
  OrgDto,
  RegisterPayload,
  RequiredPermission,
  IntegrationValidationResult,
  req,
} from './core';

export const getRequiredPermissions = () =>
  req<RequiredPermission[]>('/credentials/gcp/required-permissions');

export const authRegister = (body: RegisterPayload) =>
  req<AuthUser>('/auth/register', {method: 'POST', body: JSON.stringify(body)});

export const authLogin = (body: {email: string; password: string}) =>
  req<LoginResponse>('/auth/login', {method: 'POST', body: JSON.stringify(body)});

export const authLogout = () => req<void>('/auth/logout', {method: 'DELETE'});

export const getMyPermissions = () =>
  req<import('@/hooks/useTierPermissions').TierPermissions>('/auth/me/permissions');

export const updateProviderPreference = (provider: string) =>
  req<{provider: string}>('/auth/me/provider-preference', {
    method: 'PUT',
    body: JSON.stringify({provider}),
  });

// [F-29] (audit Sprint 1 PR-2, 2026-04-30) — verify-email returns
// LoginResponse-shaped payload now (auto-session). Caller stores
// `accessToken` and redirects, no password re-entry.
export const authVerifyEmail = (token: string) =>
  req<{accessToken: string; user: AuthUser}>(`/auth/verify-email?token=${token}`);

export const authForgotPassword = (email: string) =>
  req<{message: string}>('/auth/forgot-password', {method: 'POST', body: JSON.stringify({email})});

export const authResetPassword = (token: string, newPassword: string) =>
  req<{message: string}>('/auth/reset-password', {
    method: 'POST',
    body: JSON.stringify({token, newPassword}),
  });

export const authChangePassword = (currentPassword: string | null, newPassword: string) =>
  req<{message: string}>('/auth/change-password', {
    method: 'POST',
    body: JSON.stringify({currentPassword, newPassword}),
  });

/** Patch-style save: blank fields leave existing provider keys unchanged. */
export const updateAiKeySettings = (
  openAiApiKey: string,
  anthropicApiKey: string,
  geminiApiKey: string,
  deepSeekApiKey = '',
  fireworksApiKey = '',
) =>
  req<AuthUser>('/auth/settings/ai-keys', {
    method: 'PUT',
    body: JSON.stringify({
      openAiApiKey,
      anthropicApiKey,
      geminiApiKey,
      deepSeekApiKey,
      fireworksApiKey,
    }),
  });

/** Explicit destructive removal; raw key material is never returned. */
export const revokeAiKey = (
  provider: 'ANTHROPIC' | 'OPENAI' | 'GEMINI' | 'DEEPSEEK' | 'FIREWORKS',
) => req<void>(`/auth/settings/ai-keys/${provider.toLowerCase()}`, {method: 'DELETE'});

/** Phase 1.2 #24 — Tests a BYOK AI provider key by pinging the provider's models endpoint. */
export const validateAiKey = (
  provider: 'OPENAI' | 'ANTHROPIC' | 'GEMINI' | 'OPENROUTER' | 'DEEPSEEK' | 'FIREWORKS',
  apiKey: string,
) =>
  req<IntegrationValidationResult>('/auth/settings/ai-keys/validate', {
    method: 'POST',
    body: JSON.stringify({provider, apiKey}),
  });

/**
 * Saves org-wide AI provider API keys (ORG_ADMIN only). A blank value leaves that
 * provider's key unchanged, so the settings panel can save one provider at a time
 * without wiping the others. OpenRouter is rejected (403) on non-ENTERPRISE orgs.
 */
export const updateOrgAiKeySettings = (
  anthropicApiKey: string,
  openAiApiKey: string,
  geminiApiKey: string,
  openRouterApiKey = '',
  deepSeekApiKey = '',
  fireworksApiKey = '',
) =>
  req<OrgDto>('/org/me/settings/ai-keys', {
    method: 'PUT',
    body: JSON.stringify({
      anthropicApiKey,
      openAiApiKey,
      geminiApiKey,
      openRouterApiKey,
      deepSeekApiKey,
      fireworksApiKey,
    }),
  });
export const revokeOrgAiKey = (
  provider: 'anthropic' | 'openai' | 'gemini' | 'openrouter' | 'deepseek' | 'fireworks',
) => req<OrgDto>(`/org/me/settings/ai-keys/${provider}`, {method: 'DELETE'});
export const adminUpdateOrgApiKeys = (id: string, provided: boolean) =>
  req<OrgDto>(`/admin/orgs/${id}/api-keys`, {method: 'PUT', body: JSON.stringify({provided})});

/**
 * Mint a Personal Access Token for the CLI / VS Code clients (gcloud-style browser login).
 * The plaintext `es_pat_…` is returned ONCE; it is handed straight to the local client via the
 * loopback / vscode:// redirect and never persisted in the browser. Manage/revoke under Settings.
 */
export const createPat = (name: string) =>
  req<{token: string; meta: {id: string; name: string}}>('/auth/me/tokens', {
    method: 'POST',
    body: JSON.stringify({
      name,
      scopes: ['api:read', 'runs:write', 'projects:write', 'agent:write', 'settings:write'],
    }),
  });

export type PersonalAccessTokenMeta = {
  id: string;
  name: string;
  tokenPrefix: string;
  scopes: string;
  createdAt: string;
  lastUsedAt?: string | null;
  expiresAt?: string | null;
  revoked: boolean;
};

/** Secret-free inventory of this user's CLI / VS Code / CI tokens. */
export const listPats = () => req<PersonalAccessTokenMeta[]>('/auth/me/tokens');

/** Idempotently revoke one token owned by the authenticated user. */
export const revokePat = (tokenId: string) =>
  req<void>(`/auth/me/tokens/${encodeURIComponent(tokenId)}`, {method: 'DELETE'});
