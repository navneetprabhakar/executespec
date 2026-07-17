import type {ProviderEntry, UserModel} from '@/lib/api';

const PRODUCT_PROVIDER_IDS = new Set([
  'ANTHROPIC',
  'OPENAI',
  'GEMINI',
  'GOOGLE',
  'OPENROUTER',
  'DEEPSEEK',
  'FIREWORKS',
]);

/**
 * A catalog response is input, not authority. Old servers and stale configuration must not revive a
 * provider that the current product cannot dispatch.
 */
export function isProductProvider(provider: string | null | undefined): boolean {
  return PRODUCT_PROVIDER_IDS.has((provider ?? '').trim().toUpperCase());
}

export function productProviderCatalog(catalog: ProviderEntry[]): ProviderEntry[] {
  return catalog.filter((provider) => isProductProvider(provider.id));
}

/**
 * Join the public provider metadata with `/api/models/me`, the authoritative account-eligibility
 * contract. A null eligibility response is an older-server compatibility path only.
 */
export function eligibleAgentProviderCatalog(
  catalog: ProviderEntry[],
  userModels: UserModel[] | null,
): ProviderEntry[] {
  const productCatalog = productProviderCatalog(catalog);
  if (userModels == null) return productCatalog;
  const metadata = new Map(productCatalog.map((provider) => [provider.id.toUpperCase(), provider]));
  const eligible = new Map<string, UserModel[]>();
  for (const model of userModels) {
    if (!model.available || !isProductProvider(model.provider)) continue;
    const provider = model.provider.toUpperCase();
    eligible.set(provider, [...(eligible.get(provider) ?? []), model]);
  }
  const known = productCatalog.map((provider) => ({
    ...provider,
    models: (eligible.get(provider.id.toUpperCase()) ?? []).map(toModelEntry),
  }));
  const extra = [...eligible.entries()]
    .filter(([provider]) => !metadata.has(provider))
    .map(([provider, models]) => ({
      id: provider,
      label: provider,
      models: models.map(toModelEntry),
    }));
  return [...known, ...extra];
}

export function exactModelMatchesProvider(
  catalog: ProviderEntry[],
  provider: string,
  model: string,
): boolean {
  if (!model) return true;
  const owner = catalog.find((entry) => entry.models.some((candidate) => candidate.id === model));
  return Boolean(owner && (!provider || owner.id.toUpperCase() === provider.toUpperCase()));
}

function toModelEntry(model: UserModel) {
  return {id: model.id, tier: model.minTier, label: model.label};
}
