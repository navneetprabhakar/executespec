import {describe, expect, it} from 'vitest';

import {
  domainListFromEmail,
  formatUsd,
  isSamlConfigOnly,
  matrixModelLabel,
  matrixProviderRows,
  normalizedOrgTier,
  orgDisplayName,
  orgKeyRows,
  orgMemberDisplayName,
  orgMemberId,
  orgMemberInitials,
  orgSeatCount,
  orgSeatValue,
  parseDomainList,
} from './orgUtils';
import type * as api from '@/lib/api';

describe('orgUtils', () => {
  it('formats money and domain inputs consistently', () => {
    expect(formatUsd(null)).toBe('—');
    expect(formatUsd(12)).toBe('$12.00');
    expect(domainListFromEmail('forge@executespec.ai')).toEqual(['executespec.ai']);
    expect(domainListFromEmail('forge@gmail.com')).toEqual([]);
    expect(parseDomainList(' Acme.COM, , example.io ')).toEqual(['acme.com', 'example.io']);
  });

  it('derives org display, tier, seats, and seat usage labels', () => {
    const teamOrg: api.OrgDto = org({name: 'Acme', tier: 'team', seatCount: 4});
    const enterpriseOrg: api.OrgDto = org({name: '', tier: 'ENTERPRISE', seatCount: 99});

    expect(orgDisplayName(teamOrg)).toBe('Acme');
    expect(orgDisplayName(enterpriseOrg)).toBe('Personal org');
    expect(normalizedOrgTier(teamOrg)).toBe('TEAM');
    expect(orgSeatCount({tier: 'TEAM', seats: 3} as api.OrgDto & {seats: number})).toBe(3);
    expect(orgSeatValue(teamOrg, '', 2)).toBe('2 / 4');
    expect(orgSeatValue(enterpriseOrg, '', 7)).toBe('7 active · contract-managed');
  });

  it('normalizes member labels, ids, and initials', () => {
    expect(
      orgMemberDisplayName({firstName: 'Ada', lastName: 'Lovelace', email: 'ada@x.test'}),
    ).toBe('Ada Lovelace');
    expect(orgMemberDisplayName({email: 'grace@example.com'})).toBe('grace');
    expect(orgMemberInitials({firstName: 'Ada', lastName: 'Lovelace'})).toBe('AL');
    expect(orgMemberInitials({email: 'xy@example.com'})).toBe('XY');
    expect(orgMemberId({id: 'legacy-id'})).toBe('legacy-id');
    expect(orgMemberId({id: 'legacy-id', userId: 'user-id'})).toBe('user-id');
  });

  it('builds org provider key rows by tier', () => {
    const teamRows = orgKeyRows({tier: 'TEAM', hasOpenAiKey: true} as api.OrgDto);
    expect(teamRows.map((row) => row.provider)).toEqual(['anthropic', 'openai', 'gemini']);
    expect(teamRows.find((row) => row.provider === 'openai')?.connected).toBe(true);

    const enterpriseRows = orgKeyRows({
      tier: 'ENTERPRISE',
      hasOpenRouterKey: true,
      hasDeepSeekKey: false,
      hasFireworksKey: true,
    } as api.OrgDto);
    expect(enterpriseRows.map((row) => row.provider)).toEqual([
      'anthropic',
      'openai',
      'gemini',
      'openrouter',
      'deepseek',
      'fireworks',
    ]);
  });

  it('projects model matrix rows and labels', () => {
    const matrix: api.ModelMatrixView = {
      tier: 'ENTERPRISE',
      editable: true,
      providers: ['anthropic', 'deepseek', 'missing'],
      tiers: ['planning'],
      defaults: {'anthropic:planning': 'claude', 'deepseek:planning': 'deepseek-pro'},
      overrides: {},
      modelsByProvider: {anthropic: ['claude', 'haiku'], deepseek: ['deepseek-pro']},
      modelLabels: {claude: 'Claude'},
    };

    expect(matrixProviderRows(matrix, 'planning', {'anthropic:planning': 'haiku'})).toEqual([
      {
        key: 'anthropic:planning',
        provider: 'anthropic',
        defaultModel: 'claude',
        selectedOverride: 'haiku',
        models: ['claude', 'haiku'],
      },
      {
        key: 'deepseek:planning',
        provider: 'deepseek',
        defaultModel: 'deepseek-pro',
        selectedOverride: '',
        models: ['deepseek-pro'],
      },
    ]);
    expect(matrixModelLabel(matrix, 'claude')).toBe('Claude');
    expect(matrixModelLabel(matrix, 'deepseek-pro')).toBe('deepseek-pro');
  });

  it('detects config-only SAML providers', () => {
    expect(isSamlConfigOnly('GOOGLE_OIDC')).toBe(false);
    expect(isSamlConfigOnly('OKTA_SAML')).toBe(true);
    expect(isSamlConfigOnly('AZURE_AD_SAML')).toBe(true);
  });
});

function org(overrides: Partial<api.OrgDto>): api.OrgDto {
  return {
    id: 'org-1',
    name: 'Org',
    domainPatterns: '',
    tier: 'PRO',
    status: 'ACTIVE',
    monthlyRunCount: 0,
    isPaymentVerified: true,
    monthlyRunLimitOverride: null,
    apiKeysProvided: false,
    planExpiresAt: null,
    createdAt: '2026-06-20T00:00:00Z',
    ...overrides,
  };
}
