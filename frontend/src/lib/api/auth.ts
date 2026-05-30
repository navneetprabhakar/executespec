import {
  AuthUser,
  LoginResponse,
  OrgDto,
  RegisterPayload,
  RequiredPermission,
  ValidationResult,
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

/** Saves per-user AI provider API keys. Pass empty string to clear. */
export const updateAiKeySettings = (
  openAiApiKey: string,
  anthropicApiKey: string,
  geminiApiKey: string,
) =>
  req<AuthUser>('/auth/settings/ai-keys', {
    method: 'PUT',
    body: JSON.stringify({openAiApiKey, anthropicApiKey, geminiApiKey}),
  });

/** Phase 1.2 #24 — Tests a BYOK AI provider key by pinging the provider's models endpoint. */
export const validateAiKey = (provider: 'OPENAI' | 'ANTHROPIC' | 'GEMINI', apiKey: string) =>
  req<ValidationResult>('/auth/settings/ai-keys/validate', {
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
) =>
  req<OrgDto>('/org/me/settings/ai-keys', {
    method: 'PUT',
    body: JSON.stringify({anthropicApiKey, openAiApiKey, geminiApiKey, openRouterApiKey}),
  });
export const adminUpdateOrgApiKeys = (id: string, provided: boolean) =>
  req<OrgDto>(`/admin/orgs/${id}/api-keys`, {method: 'PUT', body: JSON.stringify({provided})});
