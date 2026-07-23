import {describe, expect, it} from 'vitest';
import type * as api from '@/lib/api';
import {
  cloudRunBlockingCopy,
  cloudRunBlockingReasons,
  isCloudRunAction,
  isConfirmableCloudRun,
  requiresProjectSelection,
} from '@/ide/agentActionPolicy';

function action(over: Partial<api.AgentTypedAction> = {}): api.AgentTypedAction {
  return {
    type: 'create_cloud_run',
    arguments: {spec: 'add tests', projectId: 'p1'},
    requiresConfirmation: true,
    billingImpact: 'run_credit_on_plan_approval',
    confirmationPrompt: 'Create a remote sandbox?',
    ...over,
  };
}

describe('agentActionPolicy', () => {
  it('confirms a fully-scoped cloud-run proposal', () => {
    expect(isConfirmableCloudRun(action())).toBe(true);
    expect(cloudRunBlockingReasons(action())).toBeNull();
  });

  it('honours a server-sent blocked context even when the payload looks complete', () => {
    const blocked = action({
      actionContext: {
        requiredContext: ['project'],
        blockingReasons: ['MISSING_WRITE_SCOPE'],
        confirmable: false,
        recommendedAction: 'SELECT_PROJECT',
        projectSelectionRequired: false,
      },
    });
    expect(isConfirmableCloudRun(blocked)).toBe(false);
    expect(cloudRunBlockingReasons(blocked)).toEqual(['MISSING_WRITE_SCOPE']);
    expect(cloudRunBlockingCopy(blocked)).toContain('permission');
  });

  it('never confirms a proposal without project scope, even with no server context', () => {
    // Turns persisted before the typed contract replay with actionContext
    // undefined. Missing context must not become confirmable by omission.
    const legacy = action({arguments: {spec: 'add tests'}});
    expect(legacy.actionContext).toBeUndefined();
    expect(isConfirmableCloudRun(legacy)).toBe(false);
    expect(requiresProjectSelection(legacy)).toBe(true);
    expect(cloudRunBlockingReasons(legacy)).toEqual(['MISSING_PROJECT_CONTEXT']);
  });

  it('rejects a blank project id', () => {
    expect(isConfirmableCloudRun(action({arguments: {spec: 's', projectId: '   '}}))).toBe(false);
  });

  it('rejects a cloud-run action that does not ask for confirmation', () => {
    expect(isConfirmableCloudRun(action({requiresConfirmation: false}))).toBe(false);
  });

  it('does not claim non-cloud-run actions', () => {
    const local = action({type: 'local_code_task'});
    expect(isCloudRunAction(local)).toBe(false);
    expect(isConfirmableCloudRun(local)).toBe(false);
    expect(cloudRunBlockingReasons(local)).toBeNull();
    expect(requiresProjectSelection(local)).toBe(false);
  });

  it('is null-safe', () => {
    expect(isConfirmableCloudRun(null)).toBe(false);
    expect(isConfirmableCloudRun(undefined)).toBe(false);
    expect(cloudRunBlockingReasons(null)).toBeNull();
  });
});
