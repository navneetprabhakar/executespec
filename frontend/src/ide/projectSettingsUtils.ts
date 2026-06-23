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
    ['UPLOADED', 'EXTRACTING', 'EMBEDDING'].includes((doc.status || '').toUpperCase()),
  );
}

export function projectDocumentDisplay(row: api.ProjectDocumentRow): ProjectDocumentDisplay {
  return {
    label: row.filename || row.pathRelative || `Document ${row.id.slice(0, 6)}`,
    status: (row.status || 'READY').toUpperCase(),
    sizeLabel: row.sizeBytes ? `${(row.sizeBytes / 1024).toFixed(1)} KB` : '—',
  };
}
