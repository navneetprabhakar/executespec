import type {ProviderEntry, UserModel} from '@/lib/api';

/**
 * Join the public provider metadata with `/api/models/me`, the authoritative account-eligibility
 * contract. A null eligibility response is an older-server compatibility path only.
 */
export function eligibleAgentProviderCatalog(
  catalog: ProviderEntry[],
  userModels: UserModel[] | null,
): ProviderEntry[] {
  if (userModels == null) return catalog;
  const metadata = new Map(catalog.map((provider) => [provider.id.toUpperCase(), provider]));
  const eligible = new Map<string, UserModel[]>();
  for (const model of userModels) {
    if (!model.available) continue;
    const provider = model.provider.toUpperCase();
    eligible.set(provider, [...(eligible.get(provider) ?? []), model]);
  }
  const known = catalog.map((provider) => ({
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
