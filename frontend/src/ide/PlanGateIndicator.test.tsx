import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {MemoryRouter, useNavigate} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import * as api from '@/lib/api';
import {PlanGateIndicator} from './PlanGateIndicator';

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

vi.mock('@/lib/api', () => ({
  getRuns: vi.fn(),
}));

const getRuns = vi.mocked(api.getRuns);

function RouteHarness() {
  const navigate = useNavigate();
  return (
    <>
      <PlanGateIndicator dir='down' />
      <button type='button' onClick={() => navigate('/result/run-1')}>
        Finish navigation
      </button>
    </>
  );
}

describe('PlanGateIndicator', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    getRuns.mockReset();
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('refreshes immediately on route changes so a completed gate is not shown for 15 seconds', async () => {
    getRuns
      .mockResolvedValueOnce({content: [{runId: 'run-1', runTitle: 'Gate'}]} as never)
      .mockResolvedValueOnce({content: []} as never);

    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/plan/run-1']}>
          <RouteHarness />
        </MemoryRouter>,
      );
    });
    await act(async () => Promise.resolve());
    expect(host.textContent).toContain('1 awaiting approval');

    const navigate = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Finish navigation',
    );
    await act(async () => navigate?.click());
    await act(async () => Promise.resolve());

    expect(getRuns).toHaveBeenCalledTimes(2);
    expect(host.textContent).not.toContain('awaiting approval');
  });
});
