import type * as api from '@/lib/api';

export type ProjectSettingsForm = {
  name: string;
  description: string;
  targetBranch: string;
  branchNaming: string;
  githubOwner: string;
  provider: string;
};

export type ProjectDocumentDisplay = {
  label: string;
  status: string;
  sizeLabel: string;
  readiness: string | null;
};

export function canEditProjectSettings(
  user: Pick<api.AuthUser, 'globalRole' | 'orgRole'> | null | undefined,
): boolean {
  return user?.globalRole === 'SUPER_ADMIN' || user?.orgRole === 'ORG_ADMIN';
}

export function projectSettingsFormFromProject(project: api.ProjectDto): ProjectSettingsForm {
  return {
    name: project.name || '',
    description: project.description || '',
    targetBranch: project.targetBranch || 'main',
    branchNaming: project.branchNamingPattern || 'execspec/{slug}-{date}',
    githubOwner: project.githubOwner || '',
    provider: project.aiProvider ? project.aiProvider.toUpperCase() : 'ANTHROPIC',
  };
}

export function isProjectDocumentIndexing(
  docs: api.ProjectDocumentRow[] | null | undefined,
): boolean {
  return (docs ?? []).some((doc) =>
    ['UPLOADED', 'EXTRACTING', 'EMBEDDING', 'INDEXING', 'RETRY_PENDING'].includes(
      (doc.status || '').toUpperCase(),
    ),
  );
}

export function projectDocumentDisplay(row: api.ProjectDocumentRow): ProjectDocumentDisplay {
  const rawStatus = (row.status || 'READY').toUpperCase();
  const status =
    rawStatus === 'READY'
      ? 'SEMANTIC READY'
      : rawStatus === 'LEXICAL_READY'
        ? 'LEXICAL READY'
        : rawStatus.replace(/_/g, ' ');
  return {
    label: row.filename || row.pathRelative || `Document ${row.id.slice(0, 6)}`,
    status,
    sizeLabel: row.sizeBytes ? `${(row.sizeBytes / 1024).toFixed(1)} KB` : '—',
    readiness: row.readinessReason || null,
  };
}
