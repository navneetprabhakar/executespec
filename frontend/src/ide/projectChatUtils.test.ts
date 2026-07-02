import {describe, expect, it} from 'vitest';

import {
  formatProjectChatStatus,
  formatProjectList,
  formatProjectRunList,
  resolveProjectChatSlashAction,
} from './projectChatUtils';

describe('projectChatUtils', () => {
  it('resolves project chat slash commands into side-effect-free actions', () => {
    const context = {projectId: 'p-1', memRuns: 2, memDigest: 'memory digest'};

    expect(resolveProjectChatSlashAction('hello', context)).toEqual({kind: 'none'});
    expect(resolveProjectChatSlashAction('/team', context)).toEqual({
      kind: 'navigate',
      to: '/org-members',
    });
    expect(resolveProjectChatSlashAction('/invite', context)).toEqual({
      kind: 'navigate',
      to: '/org-members',
    });
    expect(resolveProjectChatSlashAction('/clear', context)).toEqual({kind: 'clear'});
    expect(resolveProjectChatSlashAction('/compile', context)).toEqual({kind: 'compile'});
    expect(resolveProjectChatSlashAction('/compact', context)).toEqual({kind: 'compact'});
    expect(resolveProjectChatSlashAction('/projects', context)).toEqual({kind: 'list-projects'});
    expect(resolveProjectChatSlashAction('/runs', context)).toEqual({kind: 'list-runs'});
    expect(resolveProjectChatSlashAction('/memory', context)).toEqual({
      kind: 'memory',
      content: 'memory digest',
    });
  });

  it('formats built-in help, status, memory fallback, and provider commands', () => {
    const context = {projectId: 'project-7', memRuns: 1, memDigest: ''};

    expect(resolveProjectChatSlashAction('/help', context)).toMatchObject({kind: 'note'});
    expect(resolveProjectChatSlashAction('/status', context)).toEqual({
      kind: 'note',
      content: 'Project chat · project-7 · 1 completed work item',
    });
    expect(resolveProjectChatSlashAction('/memory', context)).toEqual({
      kind: 'memory',
      content: 'No project memory has been generated yet.',
    });
    expect(resolveProjectChatSlashAction('/provider claude', context)).toEqual({
      kind: 'provider',
      provider: 'ANTHROPIC',
    });
    expect(resolveProjectChatSlashAction('/provider unknown', context)).toEqual({
      kind: 'provider-usage',
      content: 'Usage: /provider anthropic|openai|deepseek',
    });
  });

  it('formats project chat status and list outputs with empty states and caps', () => {
    expect(formatProjectChatStatus('p-1', 0)).toBe('Project chat · p-1 · 0 completed work items');

    expect(formatProjectList([])).toBe('No projects found.');
    expect(
      formatProjectList(
        Array.from({length: 10}, (_, i) => ({
          id: `p-${i + 1}`,
          name: `Project ${i + 1}`,
        })),
      ).split('\n'),
    ).toHaveLength(8);

    expect(formatProjectRunList([])).toBe('No recent work found for this project.');
    expect(
      formatProjectRunList([
        {runId: '1234567890', status: 'COMPLETE', userRequest: 'Ship feature'},
        {runId: 'abcdefghi', status: 'FAILED', userRequest: null},
      ]),
    ).toBe('COMPLETE · 12345678 · Ship feature\nFAILED · abcdefgh · Untitled');
  });
});
