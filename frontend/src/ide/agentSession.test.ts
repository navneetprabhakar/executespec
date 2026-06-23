import {describe, expect, it} from 'vitest';
import type {AgentSessionResponse} from '@/lib/api';
import {
  agentSessionMatchesScope,
  agentSessionTitle,
  selectScopedAgentSession,
  webAgentSessionRequest,
} from './agentSession';

function session(id: string, projectId: string | null, runId: string | null): AgentSessionResponse {
  return {
    id,
    projectId,
    runId,
    repoRemote: null,
    surface: 'WEB',
    title: null,
    status: 'ACTIVE',
    defaultProvider: null,
    defaultModel: null,
    defaultExecutionMode: 'PRODUCTION',
    approvalPolicy: 'CONFIRM_MUTATIONS',
    createdAt: '2026-06-20T00:00:00Z',
    updatedAt: '2026-06-20T00:00:00Z',
  };
}

describe('agentSession helpers', () => {
  it('keeps project-scoped sessions distinct from run-scoped sessions', () => {
    const project = session('project', 'p1', null);
    const run = session('run', 'p1', 'run-1');

    expect(agentSessionMatchesScope(project, {projectId: 'p1'})).toBe(true);
    expect(agentSessionMatchesScope(run, {projectId: 'p1'})).toBe(false);
    expect(agentSessionMatchesScope(run, {projectId: 'p1', runId: 'run-1'})).toBe(true);
  });

  it('selects the exact scoped session', () => {
    const sessions = [session('run', 'p1', 'run-1'), session('project', 'p1', null)];

    expect(selectScopedAgentSession(sessions, {projectId: 'p1'})?.id).toBe('project');
    expect(selectScopedAgentSession(sessions, {projectId: 'p1', runId: 'run-1'})?.id).toBe('run');
    expect(selectScopedAgentSession(sessions, {projectId: 'p2'})).toBeNull();
  });

  it('builds the standard web session request', () => {
    expect(webAgentSessionRequest({projectId: 'p1', runId: 'run-1'})).toEqual({
      surface: 'WEB',
      projectId: 'p1',
      runId: 'run-1',
      title: 'Run run-1 agent',
      defaultExecutionMode: 'PRODUCTION',
      approvalPolicy: 'CONFIRM_MUTATIONS',
    });
    expect(agentSessionTitle({projectId: 'p1'})).toBe('Project agent');
    expect(agentSessionTitle({})).toBe('Web agent');
  });
});
