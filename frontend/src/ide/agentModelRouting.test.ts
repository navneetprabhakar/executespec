import {describe, expect, it} from 'vitest';
import {eligibleAgentProviderCatalog, exactModelMatchesProvider} from './agentModelRouting';
import type {ProviderEntry, UserModel} from '@/lib/api';

const catalog: ProviderEntry[] = [
  {
    id: 'ANTHROPIC',
    label: 'Anthropic',
    models: [{id: 'claude-sonnet', label: 'Sonnet', tier: 'PLANNING'}],
  },
  {
    id: 'OPENAI',
    label: 'OpenAI',
    models: [{id: 'gpt-frontier', label: 'GPT Frontier', tier: 'PLANNING'}],
  },
];

const accountModels: UserModel[] = [
  {
    id: 'claude-sonnet',
    provider: 'ANTHROPIC',
    label: 'Sonnet',
    minTier: 'PRO',
    available: true,
  },
  {
    id: 'gpt-frontier',
    provider: 'OPENAI',
    label: 'GPT Frontier',
    minTier: 'ENTERPRISE',
    available: false,
  },
  {
    id: 'deepseek-chat',
    provider: 'DEEPSEEK',
    label: 'DeepSeek Chat',
    minTier: 'FREE',
    available: true,
  },
];

describe('agent exact-route model catalog', () => {
  it('uses /models/me eligibility and retains eligible models outside tier-slot metadata', () => {
    const result = eligibleAgentProviderCatalog(catalog, accountModels);
    expect(result.find((provider) => provider.id === 'ANTHROPIC')?.models).toHaveLength(1);
    expect(result.find((provider) => provider.id === 'OPENAI')?.models).toHaveLength(0);
    expect(result.find((provider) => provider.id === 'DEEPSEEK')?.models[0]?.id).toBe(
      'deepseek-chat',
    );
  });

  it('rejects stale cross-provider and account-ineligible exact model pairs', () => {
    const result = eligibleAgentProviderCatalog(catalog, accountModels);
    expect(exactModelMatchesProvider(result, 'ANTHROPIC', 'claude-sonnet')).toBe(true);
    expect(exactModelMatchesProvider(result, 'OPENAI', 'claude-sonnet')).toBe(false);
    expect(exactModelMatchesProvider(result, 'OPENAI', 'gpt-frontier')).toBe(false);
    expect(exactModelMatchesProvider(result, 'OPENAI', '')).toBe(true);
  });

  it('fails closed for exact pins when authenticated eligibility returns no models', () => {
    const result = eligibleAgentProviderCatalog(catalog, []);
    expect(result.every((provider) => provider.models.length === 0)).toBe(true);
    expect(exactModelMatchesProvider(result, 'ANTHROPIC', 'claude-sonnet')).toBe(false);
  });
});
