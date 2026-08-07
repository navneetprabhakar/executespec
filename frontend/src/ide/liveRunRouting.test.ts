import {describe, expect, it} from 'vitest';
import {liveRunNavigationPath, liveRunResultPath} from './liveRunRouting';

describe('liveRunResultPath', () => {
  it.each(['COMPLETE', 'COMPLETED', 'PARTIAL', 'FAILED', 'ERROR', 'CANCELLED'])(
    'routes terminal %s work to its result',
    (status) => {
      expect(liveRunResultPath('run-1', status)).toBe('/result/run-1');
    },
  );

  it.each(['QUEUED', 'PLANNING', 'AWAITING_APPROVAL', 'APPROVAL_RESUMING', 'WORKING'])(
    'keeps non-terminal %s work on the live route',
    (status) => {
      expect(liveRunResultPath('run-1', status)).toBeNull();
    },
  );
});

describe('liveRunNavigationPath', () => {
  it.each(['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED'])(
    'still sends terminal %s work to its result',
    (status) => {
      expect(liveRunNavigationPath('run-1', status)).toBe('/result/run-1');
    },
  );

  // The defect: /live only ever navigated on terminal, so a run parked at the gate left the
  // watcher on an activity log with no plan and no approve button, waiting on an approval they
  // were never offered (dev run 350fd2fc).
  it('sends a run waiting at the plan gate to the gate', () => {
    expect(liveRunNavigationPath('run-1', 'AWAITING_APPROVAL')).toBe('/plan/run-1');
  });

  it('tolerates casing and padding around the gate status', () => {
    expect(liveRunNavigationPath('run-1', ' awaiting_approval ')).toBe('/plan/run-1');
  });

  it.each(['QUEUED', 'PLANNING', 'APPROVAL_RESUMING', 'WORKING', 'REVIEWING', 'VALIDATING'])(
    'keeps genuinely in-flight %s work on the live route',
    (status) => {
      expect(liveRunNavigationPath('run-1', status)).toBeNull();
    },
  );

  it.each([null, undefined, ''])('stays put for an unknown status (%s)', (status) => {
    expect(liveRunNavigationPath('run-1', status as string | null | undefined)).toBeNull();
  });

  it('never builds a path without a run id', () => {
    expect(liveRunNavigationPath('', 'AWAITING_APPROVAL')).toBeNull();
  });
});
