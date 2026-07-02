import type * as api from '@/lib/api';

export type UserKeyProvider = 'OPENAI' | 'ANTHROPIC' | 'GEMINI' | 'DEEPSEEK' | 'FIREWORKS';
export type OrgKeyProvider =
  | 'anthropic'
  | 'openai'
  | 'gemini'
  | 'openrouter'
  | 'deepseek'
  | 'fireworks';

export type OrgKeyRow = {
  provider: OrgKeyProvider;
  label: string;
  connected: boolean;
};

export type MatrixProviderRow = {
  key: string;
  provider: string;
  defaultModel: string;
  selectedOverride: string;
  models: string[];
};

export const TEAM_MIN_SEATS = 2;

export const KEY_PLACEHOLDER: Record<UserKeyProvider, string> = {
  ANTHROPIC: 'sk-ant-…',
  OPENAI: 'sk-…',
  GEMINI: 'AIza…',
  DEEPSEEK: 'sk-…',
  FIREWORKS: 'fw_…',
};

const CONSUMER_EMAIL_DOMAINS = new Set(['gmail.com', 'yahoo.com', 'outlook.com', 'hotmail.com']);

export function formatUsd(n?: number | null): string {
  return n == null ? '—' : `$${n.toFixed(2)}`;
}

export function domainListFromEmail(email?: string | null): string[] {
  const domain = email?.split('@')[1]?.trim().toLowerCase();
  if (!domain || CONSUMER_EMAIL_DOMAINS.has(domain)) return [];
  return [domain];
}

export function parseDomainList(value: string): string[] {
  return value
    .split(',')
    .map((domain) => domain.trim().toLowerCase())
    .filter(Boolean);
}

export function orgDisplayName(org: api.OrgDto | null | undefined): string {
  return org?.name || 'Personal org';
}

export function normalizedOrgTier(org: Pick<api.OrgDto, 'tier'> | null | undefined, fallback = '') {
  return (org?.tier || fallback || '').toUpperCase();
}

export function orgSeatCount(org: (api.OrgDto & {seats?: number}) | null | undefined): number {
  return org?.seatCount ?? org?.seats ?? 1;
}

export function isTeamWorkspaceSetupPending(
  org: (api.OrgDto & {seats?: number}) | null | undefined,
  fallbackTier: string,
): boolean {
  const tier = normalizedOrgTier(org, fallbackTier);
  return !!org?.isPersonal && (tier === 'BYOK' || tier === 'TEAM' || tier === 'ENTERPRISE');
}

export function orgSeatDisplayCount(
  org: (api.OrgDto & {seats?: number}) | null | undefined,
  fallbackTier: string,
): number {
  const tier = normalizedOrgTier(org, fallbackTier);
  const count = orgSeatCount(org);
  return tier === 'TEAM' ? Math.max(count, TEAM_MIN_SEATS) : count;
}

export function orgSeatValue(
  org: (api.OrgDto & {seats?: number}) | null | undefined,
  fallbackTier: string,
  activeMembers: number,
): string {
  const tier = normalizedOrgTier(org, fallbackTier);
  if (isTeamWorkspaceSetupPending(org, fallbackTier)) {
    if (tier === 'ENTERPRISE') return `${activeMembers} active · contract setup pending`;
    if (tier === 'TEAM') return `${activeMembers} active · team setup pending`;
    if (tier === 'BYOK') return `${activeMembers} active · BYOK workspace`;
  }
  return tier === 'ENTERPRISE'
    ? `${activeMembers} active · contract-managed`
    : `${activeMembers} / ${orgSeatDisplayCount(org, fallbackTier)}`;
}

export function orgMemberDisplayName(member: {
  firstName?: string | null;
  lastName?: string | null;
  email?: string | null;
}): string {
  return (
    [member.firstName || member.email?.split('@')[0], member.lastName].filter(Boolean).join(' ') ||
    '—'
  );
}

export function orgMemberInitials(member: {
  firstName?: string | null;
  lastName?: string | null;
  email?: string | null;
}): string {
  const initials = `${member.firstName?.[0] ?? ''}${member.lastName?.[0] ?? ''}`;
  return (initials || member.email?.slice(0, 2) || 'EX').toUpperCase();
}

export function orgMemberId(member: {id?: string | null; userId?: string | null}): string {
  return member.userId || member.id || '';
}

export function orgKeyRows(org: api.OrgDto): OrgKeyRow[] {
  const isEnterprise = normalizedOrgTier(org) === 'ENTERPRISE';
  return [
    {provider: 'anthropic', label: 'Anthropic', connected: !!org.hasAnthropicKey},
    {provider: 'openai', label: 'OpenAI', connected: !!org.hasOpenAiKey},
    ...(isEnterprise
      ? [
          {provider: 'openrouter' as const, label: 'OpenRouter', connected: !!org.hasOpenRouterKey},
          {provider: 'deepseek' as const, label: 'DeepSeek', connected: !!org.hasDeepSeekKey},
          {provider: 'fireworks' as const, label: 'Fireworks', connected: !!org.hasFireworksKey},
        ]
      : []),
  ];
}

export function isSamlConfigOnly(provider: api.SsoProvider): boolean {
  return provider === 'OKTA_SAML' || provider === 'AZURE_AD_SAML';
}

export function matrixProviderRows(
  matrix: api.ModelMatrixView,
  tier: string,
  overrides: Record<string, string>,
): MatrixProviderRow[] {
  return matrix.providers
    .filter((provider) => matrix.defaults[`${provider}:${tier}`])
    .map((provider) => {
      const key = `${provider}:${tier}`;
      return {
        key,
        provider,
        defaultModel: matrix.defaults[key],
        selectedOverride: overrides[key] ?? '',
        models: matrix.modelsByProvider[provider] ?? [],
      };
    });
}

export function matrixModelLabel(matrix: api.ModelMatrixView, model: string): string {
  return matrix.modelLabels?.[model] || model;
}
