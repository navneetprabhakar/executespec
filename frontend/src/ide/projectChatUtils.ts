import type * as api from '@/lib/api';
import {normalizeUiProvider, uiSlashHelp} from '@/lib/uiSlash';

export type ProjectChatSlashAction =
  | {kind: 'none'}
  | {kind: 'note'; content: string}
  | {kind: 'navigate'; to: string}
  | {kind: 'clear'}
  | {kind: 'compile'}
  | {kind: 'compact'}
  | {kind: 'memory'; content: string}
  | {kind: 'list-projects'}
  | {kind: 'list-runs'}
  | {kind: 'provider'; provider: string}
  | {kind: 'provider-usage'; content: string};

export function resolveProjectChatSlashAction(
  raw: string,
  context: {projectId: string; memRuns: number; memDigest: string},
): ProjectChatSlashAction {
  if (!raw.startsWith('/')) return {kind: 'none'};
  const [cmd, arg = ''] = raw.split(/\s+/, 2);
  switch (cmd) {
    case '/help':
      return {kind: 'note', content: uiSlashHelp('project')};
    case '/team':
    case '/invite':
      return {kind: 'navigate', to: '/org-members'};
    case '/clear':
      return {kind: 'clear'};
    case '/compile':
      return {kind: 'compile'};
    case '/compact':
      return {kind: 'compact'};
    case '/memory':
      return {
        kind: 'memory',
        content: context.memDigest || 'No project memory has been generated yet.',
      };
    case '/projects':
      return {kind: 'list-projects'};
    case '/runs':
      return {kind: 'list-runs'};
    case '/status':
      return {
        kind: 'note',
        content: formatProjectChatStatus(context.projectId, context.memRuns),
      };
    case '/provider': {
      const provider = normalizeUiProvider(arg);
      return provider
        ? {kind: 'provider', provider}
        : {kind: 'provider-usage', content: 'Usage: /provider anthropic|openai|gemini|deepseek'};
    }
    default:
      return {kind: 'none'};
  }
}

export function formatProjectChatStatus(projectId: string, memRuns: number): string {
  return `Project chat · ${projectId} · ${memRuns} completed work item${memRuns === 1 ? '' : 's'}`;
}

export function formatProjectList(projects: Array<Pick<api.ProjectDto, 'id' | 'name'>>): string {
  return projects.length
    ? projects
        .slice(0, 8)
        .map((project) => `${project.name} · ${project.id}`)
        .join('\n')
    : 'No projects found.';
}

export function formatProjectRunList(
  runs: Array<Pick<api.RunSummary, 'runId' | 'status' | 'userRequest'>>,
): string {
  return runs.length
    ? runs
        .map((run) => `${run.status} · ${run.runId.slice(0, 8)} · ${run.userRequest || 'Untitled'}`)
        .join('\n')
    : 'No recent work found for this project.';
}
