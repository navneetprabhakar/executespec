import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes, useNavigate} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import IdePlan from './IdePlan';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
  getRun: vi.fn(),
  getManagedAiBalance: vi.fn(),
  approveRun: vi.fn(),
  cancelRun: vi.fn(),
  editRunPlan: vi.fn(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/hooks/useSse', () => ({useSse: () => undefined}));
vi.mock('@/stores/useRunStore', () => ({
  useRunStore: (selector: (state: {activeRun: null}) => unknown) => selector({activeRun: null}),
}));
vi.mock('@/stores/useAuthStore', () => ({
  selectIsSuperAdmin: () => false,
  useAuthStore: (selector: (state: unknown) => unknown) => selector({}),
}));
vi.mock('@/hooks/useDocumentTitle', () => ({useDocumentTitle: () => undefined}));

/** SHA-256 of `{"tasks":["a"]}` / `{"tasks":["b"]}` — see planUtils.planFingerprint. */
async function sha256Hex(value: string): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

function runDetail(runId: string, planJson: string, credits: number) {
  return {
    runId,
    status: 'AWAITING_APPROVAL',
    planJson,
    creditsCharged: credits,
    planStrategy: 'SMALL',
    tasks: [],
  };
}

function Harness() {
  const navigate = useNavigate();
  return (
    <>
      <button data-testid='go-b' onClick={() => navigate('/plan/run-b')}>
        go b
      </button>
      <IdePlan />
    </>
  );
}

let host: HTMLDivElement;
let root: Root;

function text(): string {
  return host.textContent || '';
}

function button(label: string): HTMLButtonElement | undefined {
  return Array.from(host.querySelectorAll('button')).find((b) =>
    (b.textContent || '').includes(label),
  ) as HTMLButtonElement | undefined;
}

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.getManagedAiBalance.mockResolvedValue(null);
  api.approveRun.mockResolvedValue(undefined);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

async function render() {
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={['/plan/run-a']}>
        <Routes>
          <Route path='/plan/:runId' element={<Harness />} />
          <Route path='/live/:runId' element={<div>Live</div>} />
          <Route path='/runs' element={<div>Runs</div>} />
        </Routes>
      </MemoryRouter>,
    );
  });
}

describe('IdePlan approval gate identity (RR-1459)', () => {
  it('does not carry run A gate state across a runId change to run B', async () => {
    let resolveB: ((value: unknown) => void) | undefined;
    api.getRun.mockImplementation((id: string) => {
      if (id === 'run-a') return Promise.resolve(runDetail('run-a', '{"tasks":["a"]}', 3));
      return new Promise((resolve) => {
        resolveB = resolve;
      });
    });

    await render();
    expect(text()).toContain('3 credit(s)');

    // Open the revision editor and type feedback so we can prove the draft does not leak either.
    await act(async () => {
      button('Request changes')?.click();
    });
    const textarea = host.querySelector('textarea') as HTMLTextAreaElement;
    expect(textarea).toBeTruthy();

    // Route moves to run B while run B's detail has not arrived yet.
    await act(async () => {
      (host.querySelector('[data-testid="go-b"]') as HTMLButtonElement).click();
    });

    // Run A's plan, credits and revision editor must all be gone in the same frame — nothing
    // approvable may remain on screen while the displayed plan belongs to a different run.
    expect(text()).not.toContain('3 credit(s)');
    expect(text()).not.toContain('Approve and start work');
    expect(host.querySelector('textarea')).toBeNull();
    expect(text()).toContain('Loading plan');
    expect(api.approveRun).not.toHaveBeenCalled();

    // Once B arrives the gate shows B's own numbers.
    await act(async () => {
      resolveB?.(runDetail('run-b', '{"tasks":["b"]}', 9));
    });
    expect(text()).toContain('9 credit(s)');
  });

  it('binds the approval to the fingerprint of the plan actually displayed', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    await render();

    await act(async () => {
      button('Approve and start work')?.click();
    });

    expect(api.approveRun).toHaveBeenCalledWith(
      'run-a',
      true,
      3,
      undefined,
      await sha256Hex('{"tasks":["a"]}'),
    );
  });

  it('ignores a run detail that belongs to a different run than the route', async () => {
    // A server/store answer for the wrong run must never become an approvable gate.
    api.getRun.mockResolvedValue(runDetail('some-other-run', '{"tasks":["x"]}', 7));
    await render();

    expect(text()).toContain('Loading plan');
    expect(text()).not.toContain('7 credit(s)');
    expect(button('Approve and start work')).toBeUndefined();
  });
});
