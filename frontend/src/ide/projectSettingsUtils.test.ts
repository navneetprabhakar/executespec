import {describe, expect, it} from 'vitest';

import {
  canEditProjectSettings,
  isProjectDocumentIndexing,
  projectDocumentDisplay,
  projectSettingsFormFromProject,
} from './projectSettingsUtils';
import type * as api from '@/lib/api';

describe('projectSettingsUtils', () => {
  it('allows only super admins and org admins to edit project settings', () => {
    expect(canEditProjectSettings(null)).toBe(false);
    expect(canEditProjectSettings({globalRole: 'SUPER_ADMIN', orgRole: null})).toBe(true);
    expect(canEditProjectSettings({globalRole: 'USER', orgRole: 'ORG_ADMIN'})).toBe(true);
    expect(canEditProjectSettings({globalRole: 'USER', orgRole: 'DEVELOPER'})).toBe(false);
  });

  it('builds project settings form defaults from project rows', () => {
    expect(
      projectSettingsFormFromProject({
        name: 'Core',
        description: null,
        targetBranch: null,
        branchNamingPattern: null,
        githubOwner: null,
        aiProvider: 'deepseek',
      } as api.ProjectDto),
    ).toEqual({
      name: 'Core',
      description: '',
      targetBranch: 'main',
      branchNaming: 'execspec/{slug}-{date}',
      githubOwner: '',
      provider: 'DEEPSEEK',
    });
  });

  it('detects active document indexing states', () => {
    expect(isProjectDocumentIndexing(null)).toBe(false);
    expect(
      isProjectDocumentIndexing([
        {status: 'READY'},
        {status: 'FAILED'},
      ] as api.ProjectDocumentRow[]),
    ).toBe(false);
    expect(
      isProjectDocumentIndexing([
        {status: 'READY'},
        {status: 'EMBEDDING'},
      ] as api.ProjectDocumentRow[]),
    ).toBe(true);
    expect(isProjectDocumentIndexing([{status: 'RETRY_PENDING'}] as api.ProjectDocumentRow[])).toBe(
      true,
    );
  });

  it('builds document display labels with path and id fallbacks', () => {
    expect(
      projectDocumentDisplay({
        id: 'abcdef123',
        filename: 'Architecture.pdf',
        pathRelative: 'docs/Architecture.pdf',
        status: 'ready',
        sizeBytes: 1536,
      } as unknown as api.ProjectDocumentRow),
    ).toEqual({
      label: 'Architecture.pdf',
      status: 'SEMANTIC READY',
      sizeLabel: '1.5 KB',
      readiness: null,
    });
    expect(
      projectDocumentDisplay({
        id: '123456789',
        filename: null,
        pathRelative: '',
        status: null,
        sizeBytes: null,
      } as unknown as api.ProjectDocumentRow),
    ).toEqual({
      label: 'Document 123456',
      status: 'SEMANTIC READY',
      sizeLabel: '—',
      readiness: null,
    });
  });

  it('labels lexical fallback without presenting semantic readiness', () => {
    expect(
      projectDocumentDisplay({
        id: 'lexical1',
        filename: 'requirements.md',
        status: 'LEXICAL_READY',
        sizeBytes: 512,
        retrievalMode: 'LEXICAL',
        semanticSearchAvailable: false,
        lexicalSearchAvailable: true,
        readinessReason: 'SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL',
      } as api.ProjectDocumentRow),
    ).toEqual({
      label: 'requirements.md',
      status: 'LEXICAL READY',
      sizeLabel: '0.5 KB',
      readiness: 'SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL',
    });
  });
});
