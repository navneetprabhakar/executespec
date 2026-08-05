import {describe, expect, it} from 'vitest';
import {liveRunResultPath} from './liveRunRouting';

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
