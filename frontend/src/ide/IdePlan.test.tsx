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
  getPlanContractGate: vi.fn(),
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
  // RR-819 — 204 (no contract gate) is the default for the overwhelming majority of runs.
  api.getPlanContractGate.mockResolvedValue(undefined);
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
      undefined,
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

const GATE_ROWS = [
  {
    contractId: 'c-1',
    contractName: 'Orders API',
    type: 'OPENAPI',
    detectedPath: 'api/orders.yaml',
    declaredConsumers: ['acme/billing'],
    matchedPlanTasks: ['t-2'],
  },
];

function gateReport(runId: string, digest: string, requiresTypedAck = true) {
  return {
    runId,
    reportJson: JSON.stringify(GATE_ROWS),
    reportDigest: digest,
    requiresTypedAck,
    contractCount: GATE_ROWS.length,
  };
}

function ackInput(): HTMLInputElement | null {
  return host.querySelector('[data-testid="breaking-ack-input"]');
}

async function typeAck(value: string) {
  const input = ackInput();
  if (!input) throw new Error('acknowledgement input is not rendered');
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set as (
      v: string,
    ) => void;
    setter.call(input, value);
    input.dispatchEvent(new Event('input', {bubbles: true}));
  });
}

describe('IdePlan breaking-change acknowledgement gate (RR-819)', () => {
  it('shows what breaks and blocks approval until the exact literal BREAKING is typed', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(gateReport('run-a', 'DIGEST-A'));
    await render();

    // The approver must see WHAT breaks, not a bare confirmation box.
    expect(text()).toContain('Orders API');
    expect(text()).toContain('api/orders.yaml');
    expect(text()).toContain('acme/billing');

    const approve = button('Approve and start work');
    expect(approve?.disabled).toBe(true);

    await typeAck('breaking');
    expect(button('Approve and start work')?.disabled).toBe(true);
    await typeAck('BREAKIN');
    expect(button('Approve and start work')?.disabled).toBe(true);

    await typeAck('BREAKING');
    expect(button('Approve and start work')?.disabled).toBe(false);
  });

  it('refuses to approve even if the disabled button is clicked before the ack is typed', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(gateReport('run-a', 'DIGEST-A'));
    await render();

    await act(async () => {
      button('Approve and start work')?.click();
    });
    expect(api.approveRun).not.toHaveBeenCalled();
  });

  it('sends the ack bound to the digest of the report it actually fetched', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(gateReport('run-a', 'DIGEST-A'));
    await render();

    await typeAck('BREAKING');
    await act(async () => {
      button('Approve and start work')?.click();
    });

    expect(api.approveRun).toHaveBeenCalledWith(
      'run-a',
      true,
      3,
      undefined,
      await sha256Hex('{"tasks":["a"]}'),
      {ack: 'BREAKING', reportDigest: 'DIGEST-A'},
    );
  });

  it('never lets a gate report for a different run gate — or enable — this one', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    // A report the server (or a stale in-flight fetch) attributes to another run.
    api.getPlanContractGate.mockResolvedValue(gateReport('run-zzz', 'DIGEST-Z'));
    await render();

    // It must not render as this run's evidence...
    expect(host.querySelector('[data-testid="plan-contract-gate"]')).toBeNull();
    expect(text()).not.toContain('Orders API');

    // ...and it must not travel with this run's approval either.
    await act(async () => {
      button('Approve and start work')?.click();
    });
    expect(api.approveRun).toHaveBeenCalledWith(
      'run-a',
      true,
      3,
      undefined,
      await sha256Hex('{"tasks":["a"]}'),
      undefined,
    );
  });

  it('drops the acknowledgement when the route moves to another run', async () => {
    api.getRun.mockImplementation((id: string) =>
      Promise.resolve(
        id === 'run-a'
          ? runDetail('run-a', '{"tasks":["a"]}', 3)
          : runDetail('run-b', '{"tasks":["b"]}', 9),
      ),
    );
    api.getPlanContractGate.mockImplementation((id: string) =>
      Promise.resolve(id === 'run-a' ? gateReport('run-a', 'DIGEST-A') : undefined),
    );
    await render();

    await typeAck('BREAKING');
    expect(button('Approve and start work')?.disabled).toBe(false);

    await act(async () => {
      (host.querySelector('[data-testid="go-b"]') as HTMLButtonElement).click();
    });

    // Run B has no gate, so it approves with no friction — but never carrying run A's ack.
    await act(async () => {
      button('Approve and start work')?.click();
    });
    expect(api.approveRun).toHaveBeenCalledWith(
      'run-b',
      true,
      9,
      undefined,
      await sha256Hex('{"tasks":["b"]}'),
      undefined,
    );
  });

  it('separates the two 409s: missing acknowledgement vs a report that moved', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(gateReport('run-a', 'DIGEST-A'));
    api.approveRun.mockRejectedValueOnce(
      new Error(
        'The breaking-change report you acknowledged is not the one currently on this run.' +
          ' Reload the plan and review the change again before approving.',
      ),
    );
    await render();

    await typeAck('BREAKING');
    await act(async () => {
      button('Approve and start work')?.click();
    });

    expect(text()).toContain('reload');
    // The stale acknowledgement is dropped and the report is re-read, so the user re-acknowledges
    // the change they will actually ship.
    expect(ackInput()?.value).toBe('');
    expect(button('Approve and start work')?.disabled).toBe(true);
    expect(api.getPlanContractGate.mock.calls.length).toBeGreaterThan(1);
  });

  it('does not add any friction to a run with no contract gate (the 204 case)', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(undefined);
    await render();

    expect(host.querySelector('[data-testid="plan-contract-gate"]')).toBeNull();
    expect(button('Approve and start work')?.disabled).toBe(false);

    await act(async () => {
      button('Approve and start work')?.click();
    });
    expect(api.approveRun).toHaveBeenCalledWith(
      'run-a',
      true,
      3,
      undefined,
      await sha256Hex('{"tasks":["a"]}'),
      undefined,
    );
  });

  it('does not gate when the server report says no typed acknowledgement is required', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(gateReport('run-a', 'DIGEST-A', false));
    await render();

    expect(host.querySelector('[data-testid="plan-contract-gate"]')).toBeNull();
    expect(button('Approve and start work')?.disabled).toBe(false);
  });
});
