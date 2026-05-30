import {
  LibraryFacets,
  MigrationApplyResponse,
  MigrationDetail,
  MigrationDryRunResult,
  MigrationListItem,
  ReadymadeDetail,
  ReadymadeListItem,
  SpecTemplate,
  VariantResolutionResult,
  req,
} from './core';

export const listSpecTemplates = (projectId: string) =>
  req<SpecTemplate[]>(`/projects/${projectId}/templates`);
export const deleteSpecTemplate = (projectId: string, templateId: string) =>
  req<void>(`/projects/${projectId}/templates/${templateId}`, {method: 'DELETE'});

export const listMigrations = (
  filters: {category?: string; tier?: string; search?: string} = {},
) => {
  const qs = new URLSearchParams();
  if (filters.category) qs.set('category', filters.category);
  if (filters.tier) qs.set('tier', filters.tier);
  if (filters.search) qs.set('search', filters.search);
  const q = qs.toString();
  return req<MigrationListItem[]>(`/migrations${q ? `?${q}` : ''}`);
};

export const getMigrationDetail = (slug: string) =>
  req<MigrationDetail>(`/migrations/${encodeURIComponent(slug)}`);

export const migrationDryRun = (slug: string, body: {projectId: string; repoIds: string[]}) =>
  req<MigrationDryRunResult>(`/migrations/${encodeURIComponent(slug)}/dry-run`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const migrationApply = (
  slug: string,
  body: {
    projectId: string;
    repoIds: string[];
    slotValues?: Record<string, string>;
    optionsSignature?: string;
    branch?: string;
  },
) =>
  req<MigrationApplyResponse>(`/migrations/${encodeURIComponent(slug)}/apply`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const listLibrary = (
  filters: {category?: string; macroGroup?: string; tier?: string; search?: string} = {},
) => {
  const qs = new URLSearchParams();
  if (filters.category) qs.set('category', filters.category);
  if (filters.macroGroup) qs.set('macro_group', filters.macroGroup);
  if (filters.tier) qs.set('tier', filters.tier);
  if (filters.search) qs.set('search', filters.search);
  const q = qs.toString();
  return req<ReadymadeListItem[]>(`/library${q ? `?${q}` : ''}`);
};

export const getLibraryDetail = (slug: string) =>
  req<ReadymadeDetail>(`/library/${encodeURIComponent(slug)}`);

export const getLibraryFacets = () => req<LibraryFacets>(`/library/categories`);

export const resolveLibraryVariant = (slug: string, projectId: string, repoId: string) =>
  req<VariantResolutionResult>(
    `/library/${encodeURIComponent(slug)}/resolve?projectId=${encodeURIComponent(projectId)}&repoId=${encodeURIComponent(repoId)}`,
    {method: 'POST'},
  );

/** R-331 — instantiate a solution into a V2 run (slot-substituted spec → ExecutionOrchestrator). */
export const instantiateLibrarySolution = (
  slug: string,
  body: {
    projectId: string;
    repoIds: string[];
    slotValues?: Record<string, string>;
    variantId?: string;
    branch?: string;
  },
) =>
  req<{executionRunId: string; status: string; message: string}>(
    `/library/${encodeURIComponent(slug)}/instantiate`,
    {method: 'POST', body: JSON.stringify(body)},
  );

export const favouriteLibrarySolution = (slug: string) =>
  req<void>(`/library/favourite/${encodeURIComponent(slug)}`, {method: 'POST'});

export const unfavouriteLibrarySolution = (slug: string) =>
  req<void>(`/library/favourite/${encodeURIComponent(slug)}`, {method: 'DELETE'});
